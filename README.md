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

## Before and after

`dronecan.remoteid.SecureCommand`, 29 lines, a service: a request with a constant block and a bounded array, a
response with its own constants.
[source](samples/dronecan/dronecan/remoteid/64.SecureCommand.uavcan) ·
[result](AdHoc/dronecan_dronecan.cs)

```python
# DroneCAN version of MAVLink2 SECURE_COMMAND. Please see MAVLink2 spec for more details

uint32 sequence

uint32 SECURE_COMMAND_GET_SESSION_KEY = 0
uint32 SECURE_COMMAND_GET_REMOTEID_SESSION_KEY = 1
uint32 SECURE_COMMAND_REMOVE_PUBLIC_KEYS = 2
uint32 SECURE_COMMAND_GET_PUBLIC_KEYS = 3
uint32 SECURE_COMMAND_SET_PUBLIC_KEYS = 4
uint32 SECURE_COMMAND_GET_REMOTEID_CONFIG = 5
uint32 SECURE_COMMAND_SET_REMOTEID_CONFIG = 6
uint32 operation

uint8 sig_length
uint8[<=220] data

---

uint32 sequence
uint32 operation

uint8 RESULT_ACCEPTED = 0
uint8 RESULT_TEMPORARILY_REJECTED = 1
uint8 RESULT_DENIED = 2
uint8 RESULT_UNSUPPORTED = 3
uint8 RESULT_FAILED = 4
uint8 result

uint8[<=220] data
```

```csharp
public class SecureCommand_Request {
    public const uint fixed_port_id = 64;
    public const uint SECURE_COMMAND_GET_SESSION_KEY = 0;
    public const uint SECURE_COMMAND_GET_REMOTEID_SESSION_KEY = 1;
    public const uint SECURE_COMMAND_REMOVE_PUBLIC_KEYS = 2;
    public const uint SECURE_COMMAND_GET_PUBLIC_KEYS = 3;
    public const uint SECURE_COMMAND_SET_PUBLIC_KEYS = 4;
    public const uint SECURE_COMMAND_GET_REMOTEID_CONFIG = 5;
    public const uint SECURE_COMMAND_SET_REMOTEID_CONFIG = 6;
    uint sequence; // physics: monotonic and large (counter/uptime/sequence) → varint LOSES past 268 435 455; keep fixed width
    uint operation;
    byte sig_length;
    [D(220)] byte[,,] data;
}

public class SecureCommand_Response {
    public const uint fixed_port_id = 64;
    public const byte RESULT_ACCEPTED = 0;
    public const byte RESULT_TEMPORARILY_REJECTED = 1;
    public const byte RESULT_DENIED = 2;
    public const byte RESULT_UNSUPPORTED = 3;
    public const byte RESULT_FAILED = 4;
    uint sequence; // physics: …
    uint operation;
    byte result;
    [D(220)] byte[,,] data;
}

// ... in the connection: the `---` separator became a call ...
(L____________, remoteid.SecureCommand_Response) remoteid_SecureCommand(remoteid.SecureCommand_Request req);
```

The `---` separator becomes AdHoc's RPC shorthand, `uint8[<=220]` becomes `[D(220)] byte[,,]`, the DSDL
constants survive as `const`, the fixed port id becomes a constant instead of a pack id, and `sequence` carries
the varint note the converter could not decide for you.

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

**Varint: `[MinMax]` where the values are bounded, a comment where they are not.** How DSDL stores a field
decides nothing — AdHoc lays out its own frame. What matters is what DSDL says about the *values*. A `uintN`
declares a hard range, `0 … 2ᴺ-1`, and that is a claim about the values: they are uniform inside it and cannot
leave it. `[MinMax]` is the right tool for exactly that claim — it bit-packs the field to the minimal span, and
varint on a span that is already minimal would only add a continuation bit per byte.

The arithmetic decides the rest. Varint wins only while the typical distance from the base stays under about two
million, and past 268 435 455 it always loses. So a field with no hard range needs a judgement about where its
values actually sit, and DSDL does not state that. Where the field name, its `#` comment or its unit gives a
usable hint, the converter writes it on the field rather than guessing an attribute:

```csharp
uint uptime; // physics: monotonic and large (counter/uptime/sequence) → varint LOSES past 268 435 455; keep fixed width
```

Nineteen such notes appear across the eight files: monotonic counters and uptimes, where varint would lose, and
two-sided or ceiling-hugging fields, which are `[X]` / `[V]` candidates once someone who knows the data supplies
the real amplitude or bound. No varint attribute is ever invented — that decision belongs to the person refining
the file, and this only puts the question in front of them. Float fields take no varint at all; a `float32`
error term becomes an `[X]` candidate only once it is scaled to an integer.

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
