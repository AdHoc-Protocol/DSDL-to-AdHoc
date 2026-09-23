package org.unirail;

import org.unirail.adhoc.AdHocWriter;

import java.io.IOException;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.unirail.adhoc.AdHocWriter.I1;
import static org.unirail.adhoc.AdHocWriter.I2;
import static org.unirail.adhoc.AdHocWriter.brush;
import static org.unirail.adhoc.AdHocWriter.ident;
import static org.unirail.adhoc.AdHocWriter.str;

/**
 * DSDL (OpenCyphal v1 {@code .dsdl} and DroneCAN {@code .uavcan}) → AdHoc protocol description converter.
 *
 * <p>Usage: {@code java -cp out org.unirail.DSDL2AdHoc <folder> [output folder]}. The input folder is either a
 * DSDL repository (its sub-folders are root namespaces such as {@code uavcan/}, {@code reg/}) or a folder of such
 * repositories ({@code samples/opencyphal}, {@code samples/dronecan}). One {@code .cs} is written per root
 * namespace ({@code <repo>_<root>.cs}), containing every type of that root plus the transitive closure of the
 * foreign types it references (as sub-packs only).
 */
public class DSDL2AdHoc {

	public static void main(String[] args) throws Exception {
		if (args.length < 1) {
			System.out.println("Usage: java -cp out org.unirail.DSDL2AdHoc <DSDL repository or folder of repositories> [output folder]");
			return;
		}
		Path src = Paths.get(args[0]);
		Path dst = 1 < args.length ? Paths.get(args[1]) : Paths.get(System.getProperty("user.dir"), "AdHoc");
		if (!Files.isDirectory(src)) {
			System.err.println("Not a folder: " + src);
			System.exit(1);
		}
		Files.createDirectories(dst);

		// Discover repositories. A repository is a folder whose sub-folders are root namespaces (uavcan/, reg/ ...).
		// The input is taken as one repository when it carries a repository marker (.git, LICENSE, README) or when
		// none of its sub-folders does; otherwise every marked sub-folder is a repository (samples/ layout).
		List<Path> repos = new ArrayList<>();
		List<Path> marked = new ArrayList<>();
		try (Stream<Path> s = Files.list(src)) {
			s.filter(Files::isDirectory).filter(p -> hasRepoMarker(p) && isRepo(p)).sorted().forEach(marked::add);
		}
		if (hasRepoMarker(src) || marked.isEmpty()) { if (isRepo(src)) repos.add(src); }
		else repos.addAll(marked);
		if (repos.isEmpty()) {
			System.err.println("No DSDL root namespaces found under " + src.toAbsolutePath());
			System.exit(1);
		}

		int failed = 0;
		for (Path repo : repos) {
			String repoTag = ident(repo.getFileName().toString());
			Registry reg = new Registry(repoTag);
			try {
				reg.load(repo);
			} catch (Exception e) {
				failed++;
				System.err.println("FAILED to load " + repo + ": " + e);
				e.printStackTrace();
				continue;
			}
			for (String root : reg.roots()) {
				String project = repos.size() == 1 && src.equals(repo) ? ident(root) : repoTag + "_" + ident(root);
				try {
					Emitter em = new Emitter(reg, root, project);
					String cs = em.emit();
					Path out = dst.resolve(project + ".cs");
					Files.write(out, cs.getBytes(StandardCharsets.UTF_8));
					System.out.printf("%-28s -> %s  (%d types: %d messages, %d services, %d foreign sub-packs)%n",
							repo.getFileName() + "/" + root, out, em.own.size() + em.foreign.size(), em.messages, em.services, em.foreign.size());
				} catch (Exception e) {
					failed++;
					System.err.println("FAILED " + repo.getFileName() + "/" + root + ": " + e);
					e.printStackTrace();
				}
			}
		}
		if (0 < failed) System.exit(2);
	}

	static boolean hasRepoMarker(Path dir) {
		for (String m : new String[]{".git", "LICENSE", "LICENSE.md", "LICENSE.txt", "README.md", "README.rst", "README"})
			if (Files.exists(dir.resolve(m))) return true;
		return false;
	}

	static boolean isRepo(Path dir) {
		try (Stream<Path> s = Files.list(dir)) {
			return s.anyMatch(p -> Files.isDirectory(p) && !p.getFileName().toString().startsWith(".") && hasDsdl(p));
		} catch (IOException e) { return false; }
	}

	static boolean hasDsdl(Path dir) {
		try (Stream<Path> s = Files.walk(dir)) {
			return s.anyMatch(DSDL2AdHoc::isDsdlFile);
		} catch (IOException e) { return false; }
	}

	static boolean isDsdlFile(Path p) {
		String n = p.getFileName().toString();
		return Files.isRegularFile(p) && (n.endsWith(".dsdl") || n.endsWith(".uavcan"));
	}

	// ═══════════════════════════════════════════ model ═══════════════════════════════════════════

	static final class Field {
		String castMode;      // "saturated" / "truncated" / null
		String typeText;      // "uint8", "float16", "bool", "uavcan.node.Health.1.0", "Health.1.0", ...
		int voidBits;         // > 0 for `voidN` padding
		String arrayKind;     // null, "fixed" (T[N]), "le" (T[<=N]), "lt" (T[<N])
		String arrayCapExpr;  // capacity expression text
		String name;
		String doc = "";
		int line;
	}

	static final class Const {
		String typeText, name, expr, doc = "";
		Object value;        // BigInteger | Double | Boolean, evaluated lazily
		boolean evaluating, failed;
	}

	static final class Section {
		final List<Field> fields = new ArrayList<>();
		final LinkedHashMap<String, Const> consts = new LinkedHashMap<>();
		boolean union, sealed;
		String extentExpr;
		String doc = "";
	}

	static final class TypeDef {
		String repo;
		List<String> ns;        // namespace segments, root first
		String name;            // short name as in the file name ("Heartbeat", "_")
		int major = -1, minor = -1; // -1: unversioned (DroneCAN)
		Integer fixedId;        // fixed port id (Cyphal) / data type id (DroneCAN)
		String signature;       // DroneCAN `OVERRIDE_SIGNATURE 0x...` (vendor types)
		boolean service, deprecated;
		Section request = new Section(), response;
		String doc = "";
		Path file;

		String fullName() { return String.join(".", ns) + "." + name; }
		String versionKey() { return major < 0 ? "" : major + "." + minor; }
		boolean isMarker() { return name.equals("_"); }
		boolean versioned() { return 0 <= major; }
	}

	// ═══════════════════════════════════════════ registry / parser ═══════════════════════════════════════════

	static final class Registry {
		final String repo;
		final Map<String, TreeMap<String, TypeDef>> types = new LinkedHashMap<>(); // fullName → version → type
		final Map<String, String> nsDocs = new HashMap<>();                           // namespace path → doc from _.M.m.dsdl
		final List<String> rootList = new ArrayList<>();

		Registry(String repo) { this.repo = repo; }

		List<String> roots() { return rootList; }

		void load(Path repoDir) throws IOException {
			List<Path> roots;
			try (Stream<Path> s = Files.list(repoDir)) {
				roots = new ArrayList<>();
				s.filter(Files::isDirectory).filter(p -> !p.getFileName().toString().startsWith(".")).filter(DSDL2AdHoc::hasDsdl).sorted().forEach(roots::add);
			}
			for (Path root : roots) {
				rootList.add(root.getFileName().toString());
				List<Path> files;
				try (Stream<Path> s = Files.walk(root)) {
					files = new ArrayList<>();
					s.filter(DSDL2AdHoc::isDsdlFile).sorted().forEach(files::add);
				}
				for (Path f : files) {
					TypeDef t = parse(repoDir, f);
					if (t == null) continue;
					if (t.isMarker()) { // `_.M.m.dsdl` documents the namespace itself
						nsDocs.merge(String.join(".", t.ns), t.doc, (a, b) -> a.isEmpty() ? b : a);
						continue;
					}
					TypeDef prev = types.computeIfAbsent(t.fullName(), k -> new TreeMap<>(VERSION_ORDER)).put(t.versionKey(), t);
					if (prev != null) System.err.println("WARNING duplicate type " + t.fullName() + " " + t.versionKey() + " (" + f + ")");
				}
			}
		}

		TypeDef parse(Path repoDir, Path file) throws IOException {
			String fn = file.getFileName().toString();
			String base = fn.substring(0, fn.lastIndexOf('.'));
			String[] parts = base.split("\\.");
			TypeDef t = new TypeDef();
			t.repo = repo;
			t.file = file;
			int i = 0, j = parts.length;
			if (fn.endsWith(".dsdl")) { // [PortID.]Name.Major.Minor
				if (j < 3) { System.err.println("WARNING skipped (bad file name) " + file); return null; }
				t.minor = Integer.parseInt(parts[--j]);
				t.major = Integer.parseInt(parts[--j]);
			}
			if (parts[i].matches("\\d+")) t.fixedId = Integer.valueOf(parts[i++]);
			if (i != j - 1) { System.err.println("WARNING skipped (bad file name) " + file); return null; }
			t.name = parts[i];
			Path rel = repoDir.relativize(file.getParent());
			t.ns = new ArrayList<>();
			for (Path p : rel) t.ns.add(p.toString());

			List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
			Section sec = t.request;
			// paragraph state
			List<String> pendingComments = new ArrayList<>(); // comment lines not yet attached
			Object lastStmt = null;                            // Field / Const the trailing comments attach to
			boolean anyStmtInParagraph = false, anyStmtInSection = false, firstParagraph = true;

			for (int ln = 0; ln < lines.size(); ln++) {
				String raw = lines.get(ln);
				String line = raw.trim();
				if (line.isEmpty()) { // paragraph break
					flushComments(t, sec, pendingComments, lastStmt, anyStmtInParagraph, anyStmtInSection, firstParagraph);
					pendingComments.clear();
					lastStmt = null;
					if (anyStmtInParagraph || !pendingComments.isEmpty()) firstParagraph = false;
					anyStmtInParagraph = false;
					firstParagraph = firstParagraph && !anyStmtInSection && sec.doc.isEmpty() && t.doc.isEmpty() ? firstParagraph : false;
					continue;
				}
				if (line.startsWith("#")) {
					String c = stripHash(line);
					if (lastStmt != null) appendDoc(lastStmt, c);   // Cyphal style: comments follow the statement
					else pendingComments.add(c);                    // DroneCAN style: comments precede it
					continue;
				}
				// statement (maybe with a trailing comment)
				String inline = "";
				int hash = indexOfComment(line);
				if (0 <= hash) { inline = stripHash(line.substring(hash)); line = line.substring(0, hash).trim(); }

				if (line.equals("---")) {
					flushComments(t, sec, pendingComments, lastStmt, anyStmtInParagraph, anyStmtInSection, firstParagraph);
					pendingComments.clear();
					lastStmt = null;
					t.service = true;
					sec = t.response = new Section();
					anyStmtInSection = false;
					anyStmtInParagraph = false;
					firstParagraph = false;
					continue;
				}
				if (line.startsWith("OVERRIDE_SIGNATURE")) { // DroneCAN: fixed data type signature of a vendor type
					t.signature = line.substring("OVERRIDE_SIGNATURE".length()).trim();
					pendingComments.clear();
					lastStmt = null;
					anyStmtInParagraph = true;
					continue;
				}
				if (line.startsWith("@")) {
					String[] d = line.split("\\s+", 2);
					String arg = 1 < d.length ? d[1].trim() : "";
					switch (d[0]) {
						case "@union": sec.union = true; break;
						case "@sealed": sec.sealed = true; break;
						case "@extent": sec.extentExpr = arg; break;
						case "@deprecated": t.deprecated = true; break;
						case "@assert": case "@print": break;
						default: System.err.println("WARNING " + file + ":" + (ln + 1) + " unknown directive " + d[0]);
					}
					// a directive is a statement for paragraph purposes but takes no doc
					if (!pendingComments.isEmpty() && !anyStmtInSection && firstParagraph) { t.doc = joinDoc(t.doc, pendingComments); }
					pendingComments.clear();
					lastStmt = null;
					anyStmtInParagraph = true;
					anyStmtInSection = true;
					continue;
				}
				Object stmt = parseStatement(line, file, ln + 1);
				if (stmt == null) continue;
				if (stmt instanceof Field) sec.fields.add((Field) stmt);
				else {
					Const c = (Const) stmt;
					if (sec.consts.put(c.name, c) != null) System.err.println("WARNING " + file + ":" + (ln + 1) + " duplicate constant " + c.name);
				}
				// leading comments of this paragraph belong to the first statement of the paragraph
				if (!pendingComments.isEmpty()) {
					if (firstParagraph && !anyStmtInSection && t.doc.isEmpty() && !anyStmtInParagraph) {
						// DroneCAN style header directly followed by a statement without a blank line: the header is the
						// type doc only when it is "boxed" with lone '#' lines, otherwise it documents the statement.
						if (pendingComments.size() > 1 && pendingComments.get(0).isEmpty()) t.doc = joinDoc(t.doc, pendingComments);
						else appendDoc(stmt, String.join("\n", pendingComments));
					} else appendDoc(stmt, String.join("\n", pendingComments));
					pendingComments.clear();
				}
				if (!inline.isEmpty()) appendDoc(stmt, inline);
				lastStmt = stmt;
				anyStmtInParagraph = true;
				anyStmtInSection = true;
				firstParagraph = false;
			}
			flushComments(t, sec, pendingComments, lastStmt, anyStmtInParagraph, anyStmtInSection, firstParagraph);
			return t;
		}

		/** Comments of a paragraph that never got a statement: type doc (first paragraph) or section doc. */
		static void flushComments(TypeDef t, Section sec, List<String> pending, Object lastStmt, boolean anyStmt, boolean anyStmtInSection, boolean first) {
			if (pending.isEmpty()) return;
			if (!anyStmtInSection) {
				if (sec == t.request) t.doc = joinDoc(t.doc, pending);
				else sec.doc = joinDoc(sec.doc, pending);
			}
			// otherwise: a floating comment paragraph between statements - dropped (usually manual serialization notes)
		}

		static String joinDoc(String existing, List<String> lines) {
			StringBuilder sb = new StringBuilder(existing);
			for (String l : lines) {
				if (l.isEmpty() && (sb.length() == 0 || sb.charAt(sb.length() - 1) == '\n')) continue;
				if (0 < sb.length()) sb.append('\n');
				sb.append(l);
			}
			return sb.toString().trim();
		}

		static void appendDoc(Object stmt, String text) {
			if (text.isEmpty()) return;
			if (stmt instanceof Field) { Field f = (Field) stmt; f.doc = f.doc.isEmpty() ? text : f.doc + "\n" + text; }
			else { Const c = (Const) stmt; c.doc = c.doc.isEmpty() ? text : c.doc + "\n" + text; }
		}

		static String stripHash(String s) {
			s = s.trim();
			while (s.startsWith("#")) s = s.substring(1);
			return s.trim();
		}

		/** Index of a `#` that starts a comment (outside single quotes). */
		static int indexOfComment(String s) {
			boolean q = false;
			for (int i = 0; i < s.length(); i++) {
				char c = s.charAt(i);
				if (c == '\'' ) q = !q;
				else if (c == '#' && !q) return i;
			}
			return -1;
		}

		static final Pattern VOID = Pattern.compile("^void(\\d+)$");
		static final Pattern STMT = Pattern.compile(
				"^(?:(saturated|truncated)\\s+)?([A-Za-z_][\\w.]*)\\s*(?:\\[\\s*(<=|<)?\\s*([^\\]]+?)\\s*\\])?\\s+([A-Za-z_]\\w*)\\s*(?:=\\s*(.+))?$");

		static Object parseStatement(String line, Path file, int lineNo) {
			Matcher v = VOID.matcher(line);
			if (v.matches()) {
				Field f = new Field();
				f.voidBits = Integer.parseInt(v.group(1));
				f.line = lineNo;
				return f;
			}
			Matcher m = STMT.matcher(line);
			if (!m.matches()) {
				System.err.println("WARNING " + file + ":" + lineNo + " cannot parse statement: " + line);
				return null;
			}
			if (m.group(6) != null) {
				Const c = new Const();
				c.typeText = m.group(2);
				c.name = m.group(5);
				c.expr = m.group(6).trim();
				if (m.group(4) != null) System.err.println("WARNING " + file + ":" + lineNo + " array constant not supported: " + line);
				return c;
			}
			Field f = new Field();
			f.castMode = m.group(1);
			f.typeText = m.group(2);
			if (m.group(4) != null) {
				f.arrayKind = m.group(3) == null ? "fixed" : m.group(3).equals("<=") ? "le" : "lt";
				f.arrayCapExpr = m.group(4);
			}
			f.name = m.group(5);
			f.line = lineNo;
			return f;
		}

		// ───────────────────────────── resolution ─────────────────────────────

		/** Resolves a composite type reference written inside {@code ctx}; null when unknown. */
		TypeDef resolve(TypeDef ctx, String ref) {
			List<String> seg = new ArrayList<>(List.of(ref.split("\\.")));
			String version = "";
			if (2 < seg.size() && seg.get(seg.size() - 1).matches("\\d+") && seg.get(seg.size() - 2).matches("\\d+")) {
				version = seg.get(seg.size() - 2) + "." + seg.get(seg.size() - 1);
				seg = seg.subList(0, seg.size() - 2);
			} else if (2 == seg.size() && seg.get(1).matches("\\d+")) return null;
			String path = String.join(".", seg);
			// candidates: absolute, then relative to the context namespace and each of its ancestors
			List<String> candidates = new ArrayList<>();
			candidates.add(path);
			for (int n = ctx.ns.size(); 0 < n; n--) candidates.add(String.join(".", ctx.ns.subList(0, n)) + "." + path);
			for (String c : candidates) {
				TreeMap<String, TypeDef> versions = types.get(c);
				if (versions == null) continue;
				if (!version.isEmpty()) {
					TypeDef t = versions.get(version);
					if (t != null) return t;
					continue;
				}
				if (versions.containsKey("")) return versions.get("");
				return versions.lastEntry().getValue();
			}
			return null;
		}

		/** Latest version of a type by full name. */
		boolean isLatest(TypeDef t) {
			TreeMap<String, TypeDef> versions = types.get(t.fullName());
			return versions != null && versions.lastEntry().getValue() == t;
		}
	}

	/**
	 * C# contextual keywords that break a declaration when they start a member (`file.Path p;` is read as the
	 * `file` modifier) - AdHocAgent's keyword list does not contain them, so they are renamed here.
	 */
	static final Set<String> CONTEXTUAL = new HashSet<>(List.of(
			"file", "record", "required", "scoped", "partial", "dynamic", "global", "field", "init", "nint", "nuint",
			"managed", "unmanaged", "notnull", "alias", "add", "remove", "get", "set", "when", "nameof", "args", "not"));

	/** Names of org.unirail.Meta types; a DSDL entity with such a name would shadow the marker type. */
	static final Set<String> META_NAMES = new HashSet<>(List.of(
			"File", "Stream", "Host", "Actor", "Binary", "Map", "Set", "End", "Close", "Empty", "Modify", "All", "InTS",
			"InCS", "InJAVA", "InCPP", "InGO", "InRS", "Connects", "Offline", "VirtuallyConnects", "SwapHosts", "HeaderFor",
			"FieldsInjectInto", "DateTimeDef", "TimeSpanDef", "Duration", "IfSendingFrom", "Resumable", "longJS", "ulongJS", "X"));

	/** {@link AdHocWriter#ident} plus the two DSDL-specific rules above. */
	static String name(String raw) {
		String n = ident(raw);
		if (CONTEXTUAL.contains(n)) n = Character.toUpperCase(n.charAt(0)) + n.substring(1);
		if (META_NAMES.contains(n)) n = "Dsdl" + n;
		return n;
	}

	static String unique(String raw, Set<String> taken) {
		String base = name(raw), s = base;
		for (int i = 2; taken.contains(s); i++) s = base + i;
		taken.add(s);
		return s;
	}

	/**
	 * Doc comment with DSDL's hand-wrapped comment lines re-flowed into paragraphs (a line that starts a list item
	 * or follows a blank line starts a new paragraph), then emitted through {@link AdHocWriter#doc}.
	 */
	static void doc(StringBuilder sb, String indent, String text) {
		if (text == null || text.isEmpty()) return;
		StringBuilder out = new StringBuilder();
		boolean newPara = true;
		for (String l : text.split("\\r?\\n")) {
			String s = l.trim();
			if (s.isEmpty()) { newPara = true; continue; }
			boolean item = s.startsWith("- ") || s.startsWith("* ") || s.matches("^\\d+[.)] .*") || s.startsWith("⚠") || s.startsWith("@") || s.startsWith("[");
			if (out.length() > 0) out.append(newPara || item ? '\n' : ' ');
			out.append(s);
			newPara = false;
		}
		AdHocWriter.doc(sb, indent, out.toString());
	}

	static final java.util.Comparator<String> VERSION_ORDER = (a, b) -> {
		if (a.isEmpty() || b.isEmpty()) return a.compareTo(b);
		String[] x = a.split("\\."), y = b.split("\\.");
		int c = Integer.compare(Integer.parseInt(x[0]), Integer.parseInt(y[0]));
		return c != 0 ? c : Integer.compare(Integer.parseInt(x[1]), Integer.parseInt(y[1]));
	};

	// ═══════════════════════════════════════════ expressions ═══════════════════════════════════════════

	/** Evaluates DSDL constant expressions: numbers, bool, char literals, + - * / // % ** << >> & | ^ ( ), constants. */
	static final class Expr {
		final Registry reg;
		final TypeDef ctx;
		final Section sec;
		final String src;
		int pos;

		Expr(Registry reg, TypeDef ctx, Section sec, String src) { this.reg = reg; this.ctx = ctx; this.sec = sec; this.src = src; }

		static Object eval(Registry reg, TypeDef ctx, Section sec, String text) {
			Expr e = new Expr(reg, ctx, sec, text);
			Object v = e.parseOr();
			e.ws();
			if (e.pos != text.length()) throw new IllegalArgumentException("unexpected `" + text.substring(e.pos) + "`");
			return v;
		}

		void ws() { while (pos < src.length() && Character.isWhitespace(src.charAt(pos))) pos++; }

		boolean take(String op) {
			ws();
			if (src.startsWith(op, pos)) {
				// do not confuse ** with *, // with /, << with <, etc.
				pos += op.length();
				return true;
			}
			return false;
		}

		boolean peek(String op) { ws(); return src.startsWith(op, pos); }

		Object parseOr() {
			Object l = parseXor();
			while (peek("|") && !peek("||")) { take("|"); l = bit(l, parseXor(), '|'); }
			return l;
		}

		Object parseXor() {
			Object l = parseAnd();
			while (peek("^")) { take("^"); l = bit(l, parseAnd(), '^'); }
			return l;
		}

		Object parseAnd() {
			Object l = parseShift();
			while (peek("&") && !peek("&&")) { take("&"); l = bit(l, parseShift(), '&'); }
			return l;
		}

		Object parseShift() {
			Object l = parseAdd();
			while (true) {
				if (peek("<<")) { take("<<"); l = big(l).shiftLeft(big(parseAdd()).intValueExact()); }
				else if (peek(">>")) { take(">>"); l = big(l).shiftRight(big(parseAdd()).intValueExact()); }
				else return l;
			}
		}

		Object parseAdd() {
			Object l = parseMul();
			while (true) {
				if (peek("+")) { take("+"); l = arith(l, parseMul(), '+'); }
				else if (peek("-")) { take("-"); l = arith(l, parseMul(), '-'); }
				else return l;
			}
		}

		Object parseMul() {
			Object l = parseUnary();
			while (true) {
				if (peek("**")) return l; // handled in parsePow (higher precedence) - should not reach here
				if (peek("*")) { take("*"); l = arith(l, parseUnary(), '*'); }
				else if (peek("//")) { take("//"); l = arith(l, parseUnary(), 'f'); }
				else if (peek("/")) { take("/"); l = arith(l, parseUnary(), '/'); }
				else if (peek("%")) { take("%"); l = arith(l, parseUnary(), '%'); }
				else return l;
			}
		}

		Object parseUnary() {
			if (peek("-")) { take("-"); return neg(parseUnary()); }
			if (peek("+")) { take("+"); return parseUnary(); }
			if (peek("!")) { take("!"); return !bool(parseUnary()); }
			return parsePow();
		}

		Object parsePow() {
			Object base = parseAtom();
			if (peek("**")) {
				take("**");
				Object exp = parseUnary(); // right associative
				if (base instanceof BigInteger && exp instanceof BigInteger && 0 <= ((BigInteger) exp).signum())
					return ((BigInteger) base).pow(((BigInteger) exp).intValueExact());
				return Math.pow(dbl(base), dbl(exp));
			}
			return base;
		}

		Object parseAtom() {
			ws();
			if (pos >= src.length()) throw new IllegalArgumentException("unexpected end");
			char c = src.charAt(pos);
			if (c == '(') {
				pos++;
				Object v = parseOr();
				if (!take(")")) throw new IllegalArgumentException("`)` expected");
				return v;
			}
			if (c == '\'') return charLiteral();
			if (Character.isDigit(c) || c == '.' && pos + 1 < src.length() && Character.isDigit(src.charAt(pos + 1))) return number();
			if (Character.isLetter(c) || c == '_') return identifier();
			throw new IllegalArgumentException("unexpected `" + c + "`");
		}

		Object charLiteral() {
			pos++; // '
			char c = src.charAt(pos++);
			int v;
			if (c == '\\') {
				char e = src.charAt(pos++);
				switch (e) {
					case 'n': v = '\n'; break;
					case 'r': v = '\r'; break;
					case 't': v = '\t'; break;
					case '\\': v = '\\'; break;
					case '\'': v = '\''; break;
					case 'x': v = Integer.parseInt(src.substring(pos, pos + 2), 16); pos += 2; break;
					default: throw new IllegalArgumentException("bad escape \\" + e);
				}
			} else v = c;
			if (src.charAt(pos++) != '\'') throw new IllegalArgumentException("`'` expected");
			return BigInteger.valueOf(v);
		}

		Object number() {
			int start = pos;
			if (src.startsWith("0x", pos) || src.startsWith("0X", pos)) {
				pos += 2;
				while (pos < src.length() && (Character.digit(src.charAt(pos), 16) >= 0 || src.charAt(pos) == '_')) pos++;
				return new BigInteger(src.substring(start + 2, pos).replace("_", ""), 16);
			}
			if (src.startsWith("0b", pos) || src.startsWith("0B", pos)) {
				pos += 2;
				while (pos < src.length() && (src.charAt(pos) == '0' || src.charAt(pos) == '1' || src.charAt(pos) == '_')) pos++;
				return new BigInteger(src.substring(start + 2, pos).replace("_", ""), 2);
			}
			if (src.startsWith("0o", pos) || src.startsWith("0O", pos)) {
				pos += 2;
				while (pos < src.length() && (Character.digit(src.charAt(pos), 8) >= 0 || src.charAt(pos) == '_')) pos++;
				return new BigInteger(src.substring(start + 2, pos).replace("_", ""), 8);
			}
			boolean real = false;
			while (pos < src.length()) {
				char c = src.charAt(pos);
				if (Character.isDigit(c) || c == '_') pos++;
				else if (c == '.' && !real && pos + 1 < src.length() && Character.isDigit(src.charAt(pos + 1))) { real = true; pos++; }
				else if ((c == 'e' || c == 'E') && pos + 1 < src.length() && (Character.isDigit(src.charAt(pos + 1)) || src.charAt(pos + 1) == '-' || src.charAt(pos + 1) == '+')) { real = true; pos += 2; }
				else break;
			}
			String t = src.substring(start, pos).replace("_", "");
			return real ? (Object) Double.parseDouble(t) : new BigInteger(t);
		}

		Object identifier() {
			int start = pos;
			while (pos < src.length() && (Character.isLetterOrDigit(src.charAt(pos)) || src.charAt(pos) == '_' || src.charAt(pos) == '.')) pos++;
			String id = src.substring(start, pos);
			while (id.endsWith(".")) { id = id.substring(0, id.length() - 1); pos--; }
			if (id.equals("true")) return Boolean.TRUE;
			if (id.equals("false")) return Boolean.FALSE;
			int dot = id.lastIndexOf('.');
			if (dot < 0) {
				Const c = sec.consts.get(id);
				if (c == null && ctx.response != null) c = (sec == ctx.request ? ctx.response : ctx.request).consts.get(id);
				if (c == null) throw new IllegalArgumentException("unknown constant " + id);
				return constValue(reg, ctx, sec == ctx.request || ctx.response == null ? ctx.request : ctx.response, c);
			}
			String cname = id.substring(dot + 1), tref = id.substring(0, dot);
			TypeDef t = reg.resolve(ctx, tref);
			if (t == null) throw new IllegalArgumentException("unknown type " + tref);
			Const c = t.request.consts.get(cname);
			if (c == null && t.response != null) c = t.response.consts.get(cname);
			if (c == null) throw new IllegalArgumentException("unknown constant " + id);
			return constValue(reg, t, c == t.request.consts.get(cname) ? t.request : t.response, c);
		}

		// ───── arithmetic on BigInteger / Double / Boolean ─────

		static BigInteger big(Object o) {
			if (o instanceof BigInteger) return (BigInteger) o;
			if (o instanceof Double) {
				double d = (Double) o;
				if (d == Math.rint(d)) return BigInteger.valueOf((long) d);
			}
			throw new IllegalArgumentException("integer expected, got " + o);
		}

		static double dbl(Object o) {
			if (o instanceof BigInteger) return ((BigInteger) o).doubleValue();
			if (o instanceof Double) return (Double) o;
			throw new IllegalArgumentException("number expected, got " + o);
		}

		static boolean bool(Object o) {
			if (o instanceof Boolean) return (Boolean) o;
			throw new IllegalArgumentException("boolean expected, got " + o);
		}

		static Object neg(Object o) { return o instanceof BigInteger ? ((BigInteger) o).negate() : (Object) (-dbl(o)); }

		static Object bit(Object a, Object b, char op) {
			if (a instanceof Boolean && b instanceof Boolean) {
				boolean x = (Boolean) a, y = (Boolean) b;
				return op == '|' ? x | y : op == '&' ? x & y : x ^ y;
			}
			BigInteger x = big(a), y = big(b);
			return op == '|' ? x.or(y) : op == '&' ? x.and(y) : x.xor(y);
		}

		static Object arith(Object a, Object b, char op) {
			if (a instanceof BigInteger && b instanceof BigInteger) {
				BigInteger x = (BigInteger) a, y = (BigInteger) b;
				switch (op) {
					case '+': return x.add(y);
					case '-': return x.subtract(y);
					case '*': return x.multiply(y);
					case '%': return x.mod(y.abs());
					case 'f': { BigInteger[] qr = x.divideAndRemainder(y); return qr[1].signum() != 0 && (qr[1].signum() != y.signum()) ? qr[0].subtract(BigInteger.ONE) : qr[0]; }
					case '/': { BigInteger[] qr = x.divideAndRemainder(y); return qr[1].signum() == 0 ? qr[0] : (Object) (x.doubleValue() / y.doubleValue()); }
				}
			}
			double x = dbl(a), y = dbl(b);
			switch (op) {
				case '+': return x + y;
				case '-': return x - y;
				case '*': return x * y;
				case '/': return x / y;
				case 'f': return Math.floor(x / y);
				case '%': return x % y;
			}
			throw new IllegalStateException();
		}
	}

	/** Lazily evaluated, memoised constant value (null when the expression could not be evaluated). */
	static Object constValue(Registry reg, TypeDef t, Section sec, Const c) {
		if (c.value != null || c.failed) return c.value;
		if (c.evaluating) throw new IllegalArgumentException("recursive constant " + c.name);
		c.evaluating = true;
		try {
			c.value = Expr.eval(reg, t, sec, c.expr);
		} catch (RuntimeException e) {
			c.failed = true;
			System.err.println("WARNING " + t.file.getFileName() + ": constant " + c.name + " = " + c.expr + " not evaluated: " + e.getMessage());
		} finally { c.evaluating = false; }
		return c.value;
	}

	// ═══════════════════════════════════════════ emitter ═══════════════════════════════════════════

	static final class Emitter {
		final Registry reg;
		final String root, project;
		StringBuilder sb = new StringBuilder(1 << 18); // swapped while the body is emitted ahead of the header
		final Set<TypeDef> own = new LinkedHashSet<>();     // types of this root namespace
		final Set<TypeDef> foreign = new LinkedHashSet<>(); // referenced types of other roots (sub-packs only)
		final Map<TypeDef, String> csName = new HashMap<>(); // TypeDef → C# path relative to the project interface
		int messages, services;
		boolean usesDuration;
		final List<String> attributeUse = new ArrayList<>();

		Emitter(Registry reg, String root, String project) {
			this.reg = reg;
			this.root = root;
			this.project = project;
			for (TreeMap<String, TypeDef> versions : reg.types.values())
				for (TypeDef t : versions.values()) if (t.ns.get(0).equals(root)) own.add(t);
			// transitive closure of foreign references
			List<TypeDef> queue = new ArrayList<>(own);
			for (int i = 0; i < queue.size(); i++)
				for (Section s : sections(queue.get(i)))
					for (Field f : s.fields) {
						if (f.voidBits > 0 || isPrimitive(f.typeText)) continue;
						TypeDef r = reg.resolve(queue.get(i), f.typeText);
						if (r == null) continue;
						if (!r.ns.get(0).equals(root) && foreign.add(r)) queue.add(r);
					}
		}

		static List<Section> sections(TypeDef t) {
			return t.response == null ? Collections.singletonList(t.request) : List.of(t.request, t.response);
		}

		// ───────────────────────────── namespace tree ─────────────────────────────

		/** A namespace node of the emitted tree: nested namespace containers and the types declared in it. */
		static final class Ns {
			final String segment, csIdent, fullPath; // fullPath: the DSDL namespace incl. its root ("uavcan.node")
			final Map<String, Ns> children = new TreeMap<>();
			final List<TypeDef> types = new ArrayList<>();
			final Set<String> taken = new HashSet<>();

			Ns(String segment, String csIdent, String fullPath) { this.segment = segment; this.csIdent = csIdent; this.fullPath = fullPath; }
		}

		String emit() {
			// Build the tree: own types live directly under the project interface (their root segment dropped),
			// foreign types keep their root segment as the first container.
			Ns tree = new Ns("", project, root);
			for (TypeDef t : own) place(tree, t, t.ns.subList(1, t.ns.size()), root);
			for (TypeDef t : foreign) place(tree, t, t.ns, "");
			assignNames(tree, "");

			// Dashboard: every transmittable pack (own messages + RPC request/response). No ids are pinned - a pack
			// id is AdHoc's own internal matter and the agent assigns it. The DSDL fixed port id is a fact about the
			// *source's* transport, so it is kept as a `fixed_port_id` constant inside the pack instead.
			Map<String, Integer> dashboard = new TreeMap<>();
			for (TypeDef t : own) {
				String n = csName.get(t);
				if (t.service) {
					dashboard.put(n + "_Request", null);
					dashboard.put(n + "_Response", null);
				} else dashboard.put(n, null);
			}

			// The body is emitted first: it records which time aliases the fields actually used.
			StringBuilder body = new StringBuilder();
			StringBuilder keep = sb;
			sb = body;
			emitChildren(tree, I2);
			topology();
			attributes();
			sb = keep;

			AdHocWriter.fileHeader(sb, "DSDL2AdHoc", reg.repo + "/" + root + " (" + own.size() + " types) + " + foreign.size() + " referenced types of other root namespaces",
					"DSDL: OpenCyphal v1 .dsdl and DroneCAN .uavcan definitions");
			sb.append("namespace org.dsdl {\n");
			AdHocWriter.dashboard(sb, I1, dashboard);
			sb.append(I1).append("public interface ").append(project).append(" {\n");
			String nsDoc = reg.nsDocs.get(root);
			if (nsDoc != null) { sb.append(I2).append("// ").append(nsDoc.replace("\n", "\n" + I2 + "// ")).append('\n'); }
			defaultMaxLengths();
			timeAliases();
			sb.append(body);
			sb.append(I1).append("}\n");
			sb.append("}\n");
			return sb.toString();
		}

		void place(Ns node, TypeDef t, List<String> path, String fullPrefix) {
			String full = fullPrefix;
			for (String seg : path) {
				full = full.isEmpty() ? seg : full + "." + seg;
				String f = full;
				node = node.children.computeIfAbsent(seg, s -> new Ns(s, name(s), f));
			}
			node.types.add(t);
		}

		/** Gives every type a C# identifier unique within its namespace container and records its full path. */
		void assignNames(Ns node, String prefix) {
			// namespace containers first so that a type clashing with a container name gets renamed, not the container
			for (Ns c : node.children.values()) node.taken.add(c.csIdent);
			node.types.sort((a, b) -> {
				int c = a.name.compareTo(b.name);
				return c != 0 ? c : VERSION_ORDER.compare(a.versionKey(), b.versionKey());
			});
			for (TypeDef t : node.types) {
				String base = name(t.name) + (t.versioned() ? "_" + t.major + "_" + t.minor : "");
				String n = base;
				for (int i = 2; node.taken.contains(n) || t.service && (node.taken.contains(n + "_Request") || node.taken.contains(n + "_Response")); i++) n = base + i;
				node.taken.add(n);
				if (t.service) { node.taken.add(n + "_Request"); node.taken.add(n + "_Response"); }
				csName.put(t, prefix + n);
			}
			for (Ns c : node.children.values()) assignNames(c, prefix + c.csIdent + ".");
		}

		void emitChildren(Ns node, String indent) {
			for (TypeDef t : node.types) emitType(t, indent);
			for (Ns c : node.children.values()) {
				sb.append('\n');
				String d = reg.nsDocs.get(c.fullPath);
				doc(sb, indent, "Namespace " + c.fullPath + (d == null ? "" : "\n" + d));
				sb.append(indent).append("public struct ").append(c.csIdent).append(" {\n");
				emitChildren(c, indent + I1);
				sb.append(indent).append("}\n");
			}
		}

		// ───────────────────────────── types ─────────────────────────────

		void emitType(TypeDef t, String indent) {
			String n = csName.get(t);
			n = n.substring(n.lastIndexOf('.') + 1);
			if (t.service) {
				services++;
				emitPack(t, t.request, n + "_Request", indent, "Request of service " + t.fullName() + (t.versioned() ? " " + t.versionKey() : ""));
				emitPack(t, t.response, n + "_Response", indent, "Response of service " + t.fullName() + (t.versioned() ? " " + t.versionKey() : ""));
			} else {
				if (own.contains(t)) messages++;
				emitPack(t, t.request, n, indent, null);
			}
		}

		void emitPack(TypeDef t, Section sec, String name, String indent, String kind) {
			sb.append('\n');
			StringBuilder d = new StringBuilder();
			if (kind != null) d.append(kind).append("\n\n");
			if (sec == t.request || sec.doc.isEmpty()) d.append(t.doc);
			else d.append(sec.doc);
			if (t.deprecated) d.append("\n\n⚠ DEPRECATED (@deprecated)");
			if (sec.union) d.append("\n\n@union: exactly one of the fields is present (tagged union).");
			if (!own.contains(t)) d.append("\n\nReferenced from another root namespace; used as a sub-pack only.");
			if (TIMESTAMP_TYPES.contains(t.fullName())) d.append("\n\nFields of this DSDL type are emitted as an AdHoc DateTime, so this pack itself is normally unused.");
			if (DURATION_TYPES.contains(t.fullName())) d.append("\n\nFields of this DSDL type are emitted as the AdHoc Duration alias DurationSeconds, so this pack itself is normally unused.");
			doc(sb, indent, d.toString());

			List<String> attrs = new ArrayList<>();
			if (t.versioned()) attrs.add("Version(" + t.major + ", " + t.minor + ")");
			if (sec.union) attrs.add("Union");
			if (t.deprecated) attrs.add("Deprecated");
			if (sec.sealed) attrs.add("Sealed");
			if (sec.extentExpr != null) {
				try {
					BigInteger bits = Expr.big(Expr.eval(reg, t, sec, sec.extentExpr));
					attrs.add("Extent(" + bits.divide(BigInteger.valueOf(8)) + ")");
				} catch (RuntimeException e) {
					System.err.println("WARNING " + t.file.getFileName() + ": @extent " + sec.extentExpr + " not evaluated: " + e.getMessage());
				}
			}
			if (!attrs.isEmpty()) sb.append(indent).append('[').append(String.join(", ", attrs)).append("]\n");
			sb.append(indent).append("public class ").append(name).append(" {\n");
			String in = indent + I1;

			Set<String> taken = new HashSet<>();
			taken.add(name);
			if (t.fixedId != null) {
				// The DSDL fixed port id (Cyphal) / data type id (DroneCAN). Kept as metadata, never as the AdHoc
				// pack id: AdHoc assigns its own, and this value describes the source's transport, not this protocol.
				sb.append(in).append("public const uint fixed_port_id = ").append(t.fixedId).append(";\n");
				taken.add("fixed_port_id");
			}
			if (t.signature != null && sec == t.request) {
				// As a hex STRING: AdHocAgent reads every integer constant through Int64 and overflows on a `ulong`
				// literal above long.MaxValue, which most DroneCAN signatures are.
				sb.append(in).append("public const string DATA_TYPE_SIGNATURE = ").append(str(t.signature)).append(";\n");
				taken.add("DATA_TYPE_SIGNATURE");
			}
			for (Const c : sec.consts.values()) emitConst(t, sec, c, in, taken);

			int voids = 0;
			for (Field f : sec.fields) {
				if (f.voidBits > 0) { voids += f.voidBits; continue; }
				emitField(t, sec, f, in, taken);
			}
			if (0 < voids) sb.append(in).append("// ").append(voids).append(" bit(s) of `void` padding in the DSDL layout are not represented.\n");
			sb.append(indent).append("}\n");
		}

		void emitConst(TypeDef t, Section sec, Const c, String indent, Set<String> taken) {
			Object v = constValue(reg, t, sec, c);
			String name = unique(c.name, taken);
			if (v == null) {
				doc(sb, indent, c.doc + "\nNot emitted: the DSDL expression `" + c.expr + "` could not be evaluated by the converter.");
				sb.append(indent).append("// const ").append(c.typeText).append(' ').append(name).append(" = ").append(c.expr).append(";\n");
				return;
			}
			String cs;
			String lit;
			String pt = c.typeText;
			if (pt.equals("bool")) { cs = "bool"; lit = String.valueOf(Expr.bool(v)); }
			else if (pt.startsWith("float")) {
				double d = Expr.dbl(v);
				if (pt.equals("float64")) { cs = "double"; lit = doubleLiteral(d, false); }
				else { cs = "float"; lit = doubleLiteral(d, true); }
			} else {
				Matcher m = INT.matcher(pt);
				if (!m.matches()) { System.err.println("WARNING " + t.file.getFileName() + ": constant " + c.name + " of unsupported type " + pt); return; }
				BigInteger b;
				try { b = Expr.big(v); } catch (RuntimeException e) { System.err.println("WARNING " + t.file.getFileName() + ": constant " + c.name + " is not an integer: " + v); return; }
				// AdHocAgent crashes on `const short` / `const sbyte` (InvalidCastException in ConstantImpl.init_exT):
				// signed constants are emitted as int / long, unsigned ones as byte / ushort / uint / ulong.
				int bits = Integer.parseInt(m.group(2));
				boolean unsigned = !m.group(1).isEmpty();
				cs = unsigned ? csInt(true, bits) : bits <= 32 ? "int" : "long";
				lit = b.toString();
				// The agent reads every integer constant through Int64 and overflows above long.MaxValue, so such a
				// value is carried as a decimal string.
				if (0 < b.compareTo(BigInteger.valueOf(Long.MAX_VALUE))) { cs = "string"; lit = str(b.toString()); }
			}
			doc(sb, indent, c.doc);
			sb.append(indent).append("public const ").append(cs).append(' ').append(name).append(" = ").append(lit).append(";\n");
		}

		static String doubleLiteral(double d, boolean single) {
			String s = single ? Float.toString((float) d) : Double.toString(d);
			if (s.contains("Infinity") || s.contains("NaN")) s = single ? "float." + (s.startsWith("-") ? "NegativeInfinity" : s.contains("Inf") ? "PositiveInfinity" : "NaN") : "double." + (s.startsWith("-") ? "NegativeInfinity" : s.contains("Inf") ? "PositiveInfinity" : "NaN");
			else if (single) s += "f";
			return s;
		}

		void emitField(TypeDef t, Section sec, Field f, String indent, Set<String> taken) {
			List<String> attrs = new ArrayList<>();
			String type;
			boolean nullable = sec.union; // @union: exactly one member present → every member optional
			String extraDoc = "";
			if (f.castMode != null && f.castMode.equals("truncated")) attrs.add("CastMode(\"truncated\")");

			Matcher m = INT.matcher(f.typeText);
			if (f.typeText.equals("bool")) type = "bool";
			else if (f.typeText.equals("float16")) { type = "float"; attrs.add("Float16"); }
			else if (f.typeText.equals("float32")) type = "float";
			else if (f.typeText.equals("float64")) type = "double";
			else if (m.matches()) {
				boolean signed = m.group(1).isEmpty();
				int bits = Integer.parseInt(m.group(2));
				type = csInt(!signed, bits);
				if (bits != 8 && bits != 16 && bits != 32 && bits != 64) {
					if (signed) {
						BigInteger half = BigInteger.ONE.shiftLeft(bits - 1);
						attrs.add("MinMax(" + half.negate() + ", " + half.subtract(BigInteger.ONE) + ")");
					} else attrs.add("MinMax(0, " + BigInteger.ONE.shiftLeft(bits).subtract(BigInteger.ONE) + ")");
				}
			} else {
				TypeDef r = reg.resolve(t, f.typeText);
				if (r == null) {
					System.err.println("WARNING " + t.file.getFileName() + ":" + f.line + " unresolved type " + f.typeText + " - field emitted as Binary");
					type = "Binary";
					extraDoc = "\nUnresolved DSDL type " + f.typeText + ": emitted as raw bytes.";
					if (f.arrayKind == null) { f.arrayKind = "le"; f.arrayCapExpr = "255"; }
				} else if (r.service) {
					System.err.println("WARNING " + t.file.getFileName() + ":" + f.line + " service type used as a field: " + f.typeText);
					type = "Binary";
					extraDoc = "\nService type " + f.typeText + " cannot be a field: emitted as raw bytes.";
				} else if (TIMESTAMP_TYPES.contains(r.fullName())) {
					// A network-wide synchronized timestamp: AdHoc models wall-clock time natively.
					type = "DateTime";
					extraDoc = "\nDSDL type " + r.fullName() + " (microsecond timestamp) is modelled as an AdHoc DateTime.";
					nullable = false;
				} else if (DURATION_TYPES.contains(r.fullName())) {
					// A plain elapsed duration in seconds: an AdHoc Duration alias states max and precision.
					type = "DurationSeconds";
					usesDuration = true;
					extraDoc = "\nDSDL type " + r.fullName() + " (a duration in seconds) is modelled as an AdHoc Duration.";
					nullable = false;
				} else if (isEmptyType(r)) {
					// An empty pack used as a field type carries only its presence; the agent would substitute `bool`
					// itself (with a warning), so the substitution is made here explicitly.
					type = "bool";
					extraDoc = "\nPresence flag: the DSDL type " + f.typeText + " has no fields (empty type " + csName.get(r) + ").";
				} else {
					type = csName.get(r);
					nullable = false; // reference types are optional by nature
				}
			}

			if (f.arrayKind != null) {
				BigInteger cap;
				try {
					cap = Expr.big(Expr.eval(reg, t, sec, f.arrayCapExpr));
				} catch (RuntimeException e) {
					System.err.println("WARNING " + t.file.getFileName() + ":" + f.line + " array capacity `" + f.arrayCapExpr + "` not evaluated (" + e.getMessage() + "), 255 assumed");
					cap = BigInteger.valueOf(255);
					extraDoc += "\nArray capacity `" + f.arrayCapExpr + "` could not be evaluated; 255 assumed.";
				}
				if (f.arrayKind.equals("lt")) cap = cap.subtract(BigInteger.ONE);
				if (cap.signum() <= 0) cap = BigInteger.ONE;
				attrs.add("D(" + cap + ")");
				type += f.arrayKind.equals("fixed") ? "[]" : "[,,]";
				nullable = false;
			} else if (nullable) type += "?";

			String name = unique(f.name, taken);
			doc(sb, indent, f.doc + extraDoc);
			sb.append(indent);
			if (!attrs.isEmpty()) sb.append('[').append(String.join(", ", attrs)).append("] ");
			sb.append(type).append(' ').append(name).append(";\n");
		}

		static final Pattern INT = Pattern.compile("^(u?)int(\\d+)$");

		/**
		 * DSDL types that are wall-clock timestamps. A field of such a type becomes an AdHoc {@code DateTime}
		 * instead of a nested pack around a raw microsecond counter.
		 */
		static final Set<String> TIMESTAMP_TYPES = new HashSet<>(List.of(
				"uavcan.time.SynchronizedTimestamp", // Cyphal: truncated uint56 microsecond
				"uavcan.Timestamp"));                // DroneCAN: truncated uint56 usec

		/** DSDL types that are plain elapsed durations in seconds; a field of such a type becomes a Duration alias. */
		static final Set<String> DURATION_TYPES = new HashSet<>(List.of(
				"uavcan.si.unit.duration.Scalar",      // float32 second
				"uavcan.si.unit.duration.WideScalar")); // float64 second

		/** A message type without data fields (only constants / void padding). */
		static boolean isEmptyType(TypeDef t) {
			if (t.service) return false;
			for (Field f : t.request.fields) if (f.voidBits == 0) return false;
			return true;
		}

		static boolean isPrimitive(String type) {
			return type.equals("bool") || type.startsWith("float") && type.matches("float(16|32|64)") || INT.matcher(type).matches();
		}

		static String csInt(boolean unsigned, int bits) {
			if (bits <= 8) return unsigned ? "byte" : "sbyte";
			if (bits <= 16) return unsigned ? "ushort" : "short";
			if (bits <= 32) return unsigned ? "uint" : "int";
			return unsigned ? "ulong" : "long";
		}

		// ───────────────────────────── hosts, connection ─────────────────────────────

		void topology() {
			sb.append('\n').append(I2).append("// ═════════════════════════ demo topology ═════════════════════════\n\n");
			sb.append(I2).append("// DSDL describes data types, not a topology: any node may publish any message subject and any node\n");
			sb.append(I2).append("// may serve any service. The demo joins two nodes; Node_A is the service client, Node_B the server.\n\n");
			AdHocWriter.host(sb, I2, "Node_A", null);
			AdHocWriter.host(sb, I2, "Node_B", null);
			AdHocWriter.connectionOpen(sb, I2, "Bus", "Node_A", "Node_B");
			String in = I2 + I1;
			List<String> msgs = new ArrayList<>();
			for (TypeDef t : own) if (!t.service) msgs.add(csName.get(t));
			Collections.sort(msgs);
			if (!msgs.isEmpty()) {
				sb.append(in).append("// Every message type of this root namespace, either direction, no state transition.\n");
				sb.append(in).append("[_____lr_____<").append(AdHocWriter.tuple(msgs)).append(">]\n");
				sb.append(in).append("struct Messages { }\n");
			}
			List<TypeDef> svcs = new ArrayList<>();
			for (TypeDef t : own) if (t.service) svcs.add(t);
			svcs.sort((a, b) -> csName.get(a).compareTo(csName.get(b)));
			if (!svcs.isEmpty()) {
				sb.append('\n').append(in).append("// Services: Node_A calls, Node_B responds (RPC shorthand: Call → Return → End).\n");
				Set<String> taken = new HashSet<>();
				for (TypeDef t : svcs) {
					String n = csName.get(t);
					String method = unique(n.replace('.', '_'), taken);
					sb.append(in).append("(L____________, ").append(n).append("_Response) ").append(method).append('(').append(n).append("_Request req);\n");
				}
			}
			sb.append(I2).append("}\n");
		}

		/**
		 * DSDL states every array capacity explicitly, so the per-field {@code [D(N)]} always wins; this raises the
		 * project-wide fallback above AdHoc's 255 default for the rare field whose capacity could not be evaluated.
		 */
		void defaultMaxLengths() {
			sb.append('\n').append(I2).append("// Fallback caps for anything without an explicit [D(N)]; DSDL states every capacity, so this is only a floor.\n");
			sb.append(I2).append("enum _DefaultMaxLengthOf {\n");
			sb.append(I2).append(I1).append("Arrays  = 65_535,\n");
			sb.append(I2).append(I1).append("Maps    = 65_535,\n");
			sb.append(I2).append(I1).append("Sets    = 65_535,\n");
			sb.append(I2).append(I1).append("Strings = 65_535,\n");
			sb.append(I2).append("}\n");
		}

		/** The {@code Duration} alias, emitted only when a field actually uses it. */
		void timeAliases() {
			if (!usesDuration) return;
			sb.append('\n').append(I2).append("/**\n");
			sb.append(I2).append("An elapsed duration expressed in seconds by DSDL (uavcan.si.unit.duration).\n");
			sb.append(I2).append("AdHoc stores it as a step count, so the precision below is what actually travels.\n");
			sb.append(I2).append("*/\n");
			sb.append(I2).append("class DurationSeconds : Duration {\n");
			sb.append(I2).append(I1).append("public long     max       => 4_294_967_295;              // ~49 days at 1 ms\n");
			sb.append(I2).append(I1).append("public TimeSpan precision => TimeSpan.FromMilliseconds(1);\n");
			sb.append(I2).append("}\n");
		}

		void attributes() {
			sb.append('\n').append(I2).append("// ═════════════════════════ DSDL metadata attributes ═════════════════════════\n\n");
			sb.append(I2).append("// AdHoc custom attributes: carried into the generated code as constants attached to the entity.\n\n");
			AdHocWriter.attribute(sb, I2, "Version", "DSDL type version (major, minor) from the file name.", "long major, long minor");
			AdHocWriter.attribute(sb, I2, "Union", "@union: a tagged union, exactly one field is present.");
			AdHocWriter.attribute(sb, I2, "Deprecated", "@deprecated: the type is scheduled for removal.");
			AdHocWriter.attribute(sb, I2, "Sealed", "@sealed: no delimiter header, the layout is final.");
			AdHocWriter.attribute(sb, I2, "Extent", "@extent in bytes: the maximum serialized size future versions may grow to.", "long bytes");
			AdHocWriter.attribute(sb, I2, "Float16", "The DSDL field is a 16-bit IEEE 754 float (carried as float).");
			AdHocWriter.attribute(sb, I2, "CastMode", "Explicit DSDL cast mode of the field (\"truncated\"; saturated is the default).", "string mode");
		}
	}
}
