package accept.rawtypes

/** The same two generic interfaces as [ConflictingProvider], each narrowed by its own sub-interface. */
interface EntryProvider : Provider<Entry>

interface ItemProvider : OtherProvider<Item>

/**
 * The **refusal** the fallback must make, reproduced by javac itself: the diagnostic names
 * `EntryProvider` and `ItemProvider`, both in this unit's clause — but spelled *without* type
 * arguments, because the conflicting arguments (`Provider<Entry>` and `OtherProvider<Item>`) are one
 * level up in their own `extends` clauses. There is nothing in *this* unit's `implements` clause to
 * make raw, so it must be left byte-identical and reported with javac's own diagnostic. This is the
 * shape the real module's `StorageCategory`, `EquipmentCategory` and every `*DeviceVariant` enum hit
 * (`types IEquipmentDeviceVariant and IGateDeviceVariant are incompatible`, neither name in the
 * enum's own clause with arguments).
 */
abstract class RefusedSupertypes : EntryProvider, ItemProvider
