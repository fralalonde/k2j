package accept.defaultargs

import java.util.UUID

/**
 * The `super(...)` form of the same defect: the delegation argument list references a synthetic
 * local that the body declares below it.
 *
 * Kotlin compiles `super(arrayListOf(UUID.randomUUID()))` by building the vararg array first and
 * spilling it into a local; FernFlower then emits
 *
 * ```
 * public RandomIdList(int count) {super((Collection)CollectionsKt.arrayListOf(var2));
 *    UUID[] var2 = new UUID[]{UUID.randomUUID()};
 * }
 * ```
 *
 * javac: `cannot find symbol: variable var2` — the local is declared after the only statement Java
 * allows to come first. This is the shape `AssetBarcodeList.failure.txt` reports on the real
 * module (`cannot find symbol: variable var2`, same body).
 */
class RandomIdList : ArrayList<UUID> {

    constructor(count: Int) : super(arrayListOf(UUID.randomUUID()))
}
