# DSDL-to-AdHoc — OpenCyphal and DroneCAN DSDL → AdHoc protocol description

> One of the [**converters to AdHoc protocol**](https://github.com/AdHoc-Protocol#converters-to-adhoc-protocol).
> Take a protocol you already have, get an [AdHoc](https://github.com/AdHoc-Protocol/AdHoc-protocol) description,
> open it in the Observer. The result is a starting point you refine by hand, not a finished protocol.

Converts **DSDL** data type definitions into [AdHoc](https://github.com/AdHoc-Protocol) protocol-description
`.cs` files. DSDL is the schema language of [Cyphal](https://opencyphal.org/) (files `Name.Major.Minor.dsdl`)
and of its predecessor [DroneCAN](https://dronecan.github.io/) (files `Name.uavcan`) — the two open protocols
used for intra-vehicle buses in drones, robots and spacecraft.

DSDL is the closest match to what AdHoc does natively: both describe bit-exact wire layouts. Arbitrary-width
integers (`uint3`, `int37`) map straight onto AdHoc's `[MinMax]` bit-packing, `@union` onto optional fields,
synchronized timestamps onto `DateTime`, and DSDL services onto AdHoc's RPC shorthand.

## Source links (all verified)

| What | Where |
|:--|:--|
| Cyphal regulated data types (samples) | https://github.com/OpenCyphal/public_regulated_data_types |
| DroneCAN data types (samples) | https://github.com/dronecan/DSDL |
| Cyphal specification, including the DSDL grammar | https://github.com/OpenCyphal/specification |
| pydsdl — the reference DSDL front end | https://github.com/OpenCyphal/pydsdl |
| Cyphal home page | https://opencyphal.org/ |
| DroneCAN home page | https://dronecan.github.io/ |

## Commands

```bash
./fetch-samples.sh          # clones both repositories into samples/ (257 .dsdl + 147 .uavcan)
./build.sh                  # compiles src/ into out/ and converts samples/ into AdHoc/
./validate.sh AdHoc         # AdHocAgent parse-only check of every generated .cs
```

Converter CLI: `java -cp out org.unirail.DSDL2AdHoc <repository or folder of repositories> [output folder]`
(output defaults to `<cwd>/AdHoc`, exit code 2 if any input failed).

## Output

One `.cs` per **root namespace** per repository, named `<repo>_<root>.cs`. Each file holds every type of that
root plus the transitive closure of foreign types it references (those appear as sub-packs only, never as
transmittable packs). DSDL sub-namespaces become nested `public struct` containers, so
`uavcan/node/port/List.1.0.dsdl` is emitted as `node.port.List_1_0`.

| File | Types | Messages | Services | Foreign |
|:--|--:|--:|--:|--:|
| `opencyphal_uavcan.cs` | 189 | 166 | 23 | 0 |
| `opencyphal_reg.cs` | 90 | 63 | 0 | 27 |
| `dronecan_uavcan.cs` | 86 | 69 | 17 | 0 |
| `dronecan_com.cs` | 29 | 19 | 10 | 0 |
| `dronecan_dronecan.cs` | 16 | 14 | 1 | 1 |
| `dronecan_ardupilot.cs` | 15 | 14 | 0 | 1 |
| `dronecan_mppt.cs` | 2 | 1 | 1 | 0 |
| `dronecan_cuav.cs` | 1 | 1 | 0 | 0 |

## Mapping

| DSDL | AdHoc | Notes |
|:--|:--|:--|
| `bool` | `bool` | |
| `uintN` / `intN`, N ∈ {8,16,32,64} | `byte`/`ushort`/`uint`/`ulong`, `sbyte`/`short`/`int`/`long` | |
| `uintN` / `intN`, other N | same type widened + `[MinMax(0, 2ᴺ-1)]` / `[MinMax(-2ᴺ⁻¹, 2ᴺ⁻¹-1)]` | AdHoc bit-packs to exactly N bits, so the wire layout matches DSDL |
| `float16` | `float` + `[Float16]` | AdHoc has no 16-bit float; the attribute records the source width |
| `float32` / `float64` | `float` / `double` | |
| `T[N]` | `[D(N)] T[]` | constant length |
| `T[<=N]`, `T[<N]` | `[D(N)] T[,,]` / `[D(N-1)] T[,,]` | variable length list |
| `uavcan.time.SynchronizedTimestamp`, DroneCAN `uavcan.Timestamp` | `DateTime` | AdHoc models wall-clock time natively; the raw `uint56` microsecond counter is not carried through |
| `uavcan.si.unit.duration.Scalar` / `.WideScalar` | `class DurationSeconds : Duration { max; precision; }` alias | an elapsed duration, emitted only when a field uses it |
| composite reference | field of that pack's type | resolved absolutely, then relative to each enclosing namespace, exactly as pydsdl does |
| composite with no fields | `bool` presence flag | the agent substitutes `bool` for an empty pack anyway; done explicitly here |
| `voidN` | dropped, noted in a comment | padding has no AdHoc equivalent; the pack records how many bits were dropped |
| `@union` | `[Union]` + every field optional (`T?`) | a tagged union: exactly one member is present |
| constants | `public const` inside the pack | full DSDL expression evaluator: `**`, `//`, `<<`, bitwise ops, char literals, cross-type references like `ServiceID.1.0.MAX` |
| `---` (service) | `<Name>_Request` / `<Name>_Response` packs + `(L____________, X_Response) X(X_Request req);` | Node_A is the client, Node_B the server |
| fixed port / data type id | `public const uint fixed_port_id` inside the pack | see below |
| `@extent`, `@sealed`, `@deprecated`, version | `[Extent(bytes)]`, `[Sealed]`, `[Deprecated]`, `[Version(major, minor)]` | custom attributes, materialised as constants in the generated code |
| `truncated` cast mode | `[CastMode("truncated")]` | `saturated` is the default and is not emitted |
| DroneCAN `OVERRIDE_SIGNATURE` | `public const string DATA_TYPE_SIGNATURE` | a **string**: the values exceed `long.MaxValue` (see limitations) |
| `_.M.m.dsdl` | doc comment on the namespace container | these files document a namespace, they are not types |

Several versions of one type coexist: names carry the version (`GetInfo_0_1`, `GetInfo_0_2`).

**No port ids are pinned into the Dashboard.** A pack id is AdHoc's own internal matter: the agent assigns and
maintains it, and the Dashboard is the user's workspace for tagging and routing, not a place to mirror another
protocol's numbering. The DSDL fixed port id (Cyphal) / data type id (DroneCAN) describes the *source's*
transport, so it is preserved as `public const uint fixed_port_id` inside the pack — 212 of them across the eight
files — where a migration can audit it and generated code can read it. Every generated file contains zero
`id = '` entries.

**No varint attributes are emitted.** `[A]`, `[V]` and `[X]` tell AdHoc where a number's values *cluster*, and
DSDL never says that — it states an exact bit width instead, which is a hard uniform range. That fact is carried
by `[MinMax]`, which bit-packs the field to exactly the width DSDL used. Adding a varint attribute here would be
a guess, and on a uniformly distributed field it makes the wire larger.

Each file declares `_DefaultMaxLengthOf = 65_535` for arrays, maps, sets and strings. DSDL states every capacity
explicitly, so the per-field `[D(N)]` always wins; this only raises the floor above AdHoc's 255 default for a
field whose capacity expression could not be evaluated.

## Validation

```
dronecan_ardupilot               OK
dronecan_com                     OK
dronecan_cuav                    OK
dronecan_dronecan                OK
dronecan_mppt                    OK
dronecan_uavcan                  OK
opencyphal_reg                   OK
opencyphal_uavcan                OK
```

All eight files pass `AdHocAgent` parse-only validation with no errors and no warnings. The converter itself
reports no warnings on the current samples.

## Limitations

- **`void` padding is dropped.** AdHoc computes its own layout, so DSDL padding bits cannot be reproduced. The
  byte-for-byte wire format therefore differs from DSDL; the *data model* is preserved, not the encoding. This is
  inherent: AdHoc is a different wire format, not a DSDL codec.
- **`@assert` and `@print` are ignored.** They constrain the DSDL layout, which is not reproduced.
- **`@extent` is recorded, not enforced.** AdHoc has no delimited-type mechanism, so extent is metadata only.
- **Constants above `long.MaxValue` become strings.** AdHocAgent reads every integer constant through `Int64`
  and overflows otherwise. Affects DroneCAN `OVERRIDE_SIGNATURE` values.
- **Signed constants are widened to `int`/`long`.** `const short` and `const sbyte` crash AdHocAgent
  (`InvalidCastException` in `ConstantImpl.init_exT`), so narrow signed constants are emitted as `int`.
- **Names are renamed where C# or AdHoc demands.** Besides the agent's own keyword list, C# contextual keywords
  (`file`, `record`, `set`, …) and `org.unirail.Meta` type names (`File`, `Map`, `Set`, …) are prefixed or
  capitalised, so `uavcan.file` becomes the container `File` and its types `DsdlFile.*`.
- **The topology is a demo.** DSDL describes data types only; any node may publish any subject. The generated
  connection joins two generic nodes with one bidirectional non-transitional state for all messages, plus one
  RPC method per service. Replace it with real hosts and branches for a real deployment.
