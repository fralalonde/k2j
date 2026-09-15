package org.example.k2j.core

import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.Handle
import org.objectweb.asm.Label
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes
import java.nio.file.Files
import java.nio.file.Path

/**
 * Re-materialises the interface-default delegating methods FernFlower drops.
 *
 * Kotlin writes an implementing class's inherited interface *default* as a real method: a bridge that
 * delegates into the interface (`invokespecial IFace.m`) or, with the DefaultImpls JVM mode, into the
 * interface's `IFace$DefaultImpls` holder (`invokestatic`). That member is not redundant — Java's
 * rules for inheriting interface members are stricter than Kotlin's, so the class file needs it and
 * javac refuses the generated class without it:
 *
 * ```
 * Foo.java:8:8: Foo is not abstract and does not override abstract method getItems() in Context
 * ```
 *
 * FernFlower deletes it (its bridge-removal pass drops `ACC_BRIDGE` members), so a unit whose bytecode
 * was perfectly valid no longer satisfies its own interface and the compile gate reports a defect that
 * belongs to the decompiler, not to the code. On the real `target module` module this omission was the
 * single largest defect family.
 *
 * Why Kotlin emits the bridge at all: Kotlin's `List<out E>` is covariant while Java's generics are
 * invariant, so a Kotlin interface can override `val items: List<Entry>` with `val items: List<Item>`
 * where `Item : Entry`. Kotlin calls that a valid override; Java does not, because `List<Item>` is not
 * a subtype of `List<Entry>`. Kotlin papers the difference over with an explicit delegating method in
 * the implementing class — the very member javac needs to see the abstract declaration satisfied.
 *
 * ## What this pass is, and is not
 *
 * It is **not** a text normalizer: the authority is the class file, which declares the method, its
 * descriptor, its generic `Signature` and the delegation its body performs. The generated text
 * supplies one fact — whether the method is already declared — and the pass is append-only: an
 * existing member is never edited, reordered or reformatted, and a unit whose text already declares
 * the method comes back unchanged (idempotent).
 *
 * ## The rule, in full
 *
 * A declared method of the class file is a **candidate** when its body is a *pure delegation* to an
 * interface default: `aload_0`, then one load per parameter at its own slot, then
 * `invokespecial IFace.m` (`itf = true`) or `invokestatic IFace$DefaultImpls.m`, then at most one
 * `checkcast`, then the return matching the descriptor. Nothing else may appear in the body.
 *
 * For every candidate the generated text does not already declare, **all** of the following must be
 * proven, or [synthesize] returns its input **byte for byte**:
 *
 *  1. **target** — the `invokespecial` form needs `IFace` to be a *direct* superinterface of the class
 *     (Java only allows `IFace.super.m(...)` for one) and `m` to resolve in `IFace`'s supertype
 *     closure to a *non-abstract* method (a default). When that fails, the interface's
 *     `$DefaultImpls` holder is used if it declares the matching static method; failing both, the unit
 *     is refused;
 *  2. **return type** — our descriptor's return type is identical to, or provably (through the class
 *     files) a subtype of, the return type of *every* same-signature declaration in the class's
 *     supertype closure. Two interfaces declaring one signature with unrelated returns cannot be
 *     expressed in Java at all, and this rule refuses such a class instead of writing a member javac
 *     would reject;
 *  3. **need** — at least one of those declarations is abstract. A class whose interface closure only
 *     carries defaults is left alone: javac demands nothing there, and appending an override would be
 *     a change of behaviour this pass cannot justify;
 *  4. **no superclass declaration** — no *class* in the closure declares the signature. The inherited
 *     implementation already satisfies the interface, and an override delegating to the interface
 *     default would silently bypass it;
 *  5. **renderable** — the descriptor decides the *types* that are written. The method's `Signature`
 *     attribute is used only where every same-signature declaration in the closure spells the type the
 *     same way; anywhere else the type is written as its erasure. This is not a shortcut: the narrowed
 *     `List<Item>` a bridge's signature carries is precisely what Java refuses (its generics are
 *     invariant), so rendering the signature there would append a member javac rejects all over again
 *     — the raw `java.util.List` is the faithful spelling, and it overrides every declaration. A
 *     signature that mentions a type variable, or whose erasure disagrees with the descriptor, cannot
 *     be rendered as written and refuses the unit rather than being guessed;
 *  6. **all or nothing** — if *any* candidate fails, nothing is appended at all. A partially repaired
 *     unit still fails the gate, and a guess is worse than the report the gate already makes.
 *
 * An accepted candidate is appended as a plain override delegating exactly where the bytecode
 * delegated:
 *
 * ```java
 *    public java.util.List getItems() {
 *       return Context.super.getItems();
 *    }
 * ```
 *
 * Types are written fully qualified, so no import is needed and no name in the unit can shadow them,
 * and parameters are named `arg0..argN` — their names do not matter for overriding and the class file
 * carries none for these compiler-generated members.
 */
object InterfaceDefaultSynthesis {

    /** The sentinel a body gets for an instruction this pass does not model, so it cannot match. */
    private const val UNMODELLED = -1

    /** The holder class Kotlin generates beside an interface for its JVM-default-disabled callers. */
    private const val SUFFIX = "\$DefaultImpls"

    /**
     * Returns [javaText] with the delegating overrides [classFile] declares and the text lacks
     * appended, or [javaText] unchanged when any candidate cannot be proven. Never throws.
     *
     * @param classFile the unit's top-level class file — the class whose interfaces must be satisfied
     * @param classesRoot directory of compiled `.class` files, where every referenced type is resolved
     * @param javaText the generated Java text the parse/compile gate is about to see
     */
    fun synthesize(classFile: Path, classesRoot: Path, javaText: String): String =
        try {
            repair(classFile, classesRoot, javaText)
        } catch (t: Throwable) {
            // Never throws: an unprovable shape must reach the gate unchanged so it is reported,
            // rather than failing the whole run with an exception from a repair pass.
            javaText
        }

    private fun repair(classFile: Path, classesRoot: Path, javaText: String): String {
        val types = Types(classesRoot)
        val owner = types.load(classFile) ?: return javaText
        // Interfaces need their super calls qualified too: a Kotlin interface default that overrides
        // and super-calls a parent default decompiles with the same ownerless `super.m()` FernFlower
        // drops in classes, and javac rejects it with the same `cannot find symbol: variable super`.
        // The append loop below stays class-only — an interface's own defaults are kept by FernFlower
        // as `default` methods, so there is nothing to synthesize back.
        val qualifiedText = qualifyInterfaceSuperCalls(owner, javaText)
        if (owner.isInterface) return qualifiedText

        val appended = mutableListOf<String>()
        for (method in owner.methods) {
            if (method.name.startsWith("<")) continue
            if (method.isAbstract || method.isStatic || method.isNative || !method.isPublic) continue
            val delegation = delegationOf(method) ?: continue
            if (declaresMethod(qualifiedText, method.name, descriptorParameters(method.descriptor))) continue
            appended += prove(owner, method, delegation, types) ?: return qualifiedText
        }
        if (appended.isEmpty()) return qualifiedText

        val closing = qualifiedText.lastIndexOf('}')
        if (closing < 0) return qualifiedText
        return qualifiedText.substring(0, closing) +
            appended.joinToString(separator = "") { "\n$it\n" } +
            qualifiedText.substring(closing)
    }

    /**
     * Restores the owner FernFlower drops from `Interface.super.m()` calls already present in a
     * non-bridge method. The class file is authoritative: only INVOKESPECIAL calls marked as interface
     * calls to a direct superinterface qualify. Text and bytecode occurrence counts must agree, so an
     * overload, duplicate spelling, or unexplained `super.m()` is left untouched.
     */
    private fun qualifyInterfaceSuperCalls(owner: ClassInfo, javaText: String): String {
        data class Call(val interfaceName: String, val methodName: String)

        val calls = owner.methods.flatMap { method ->
            method.body.mapNotNull { insn ->
                if (insn.opcode != Opcodes.INVOKESPECIAL) return@mapNotNull null
                val parts = insn.operand.split('|')
                if (parts.size != 4 || parts[3] != "true" || parts[0] !in owner.interfaces) return@mapNotNull null
                Call(parts[0], parts[1])
            }
        }.groupBy { it.methodName }

        var repaired = javaText
        for ((methodName, namedCalls) in calls) {
            val owners = namedCalls.map { it.interfaceName }.distinct()
            if (owners.size != 1) continue
            val pattern = Regex("(?<![A-Za-z0-9_.$])super\\.${Regex.escape(methodName)}\\(")
            val matches = pattern.findAll(repaired).toList()
            if (matches.isEmpty() || matches.size != namedCalls.size) continue
            val interfaceName = owners.single().substringAfterLast('/').replace('$', '.')
            repaired = pattern.replace(repaired, "$interfaceName.super.$methodName(")
        }
        return repaired
    }

    // -- the class file --------------------------------------------------------------------------

    /** One instruction: the opcode plus the operand the classification needs. */
    private class Insn(val opcode: Int, val operand: String)

    /** One declared method, reduced to what a candidate decision needs. */
    private class MethodInfo(
        val access: Int,
        val name: String,
        val descriptor: String,
        val signature: String?,
        val body: List<Insn>
    ) {
        val isAbstract: Boolean get() = access and Opcodes.ACC_ABSTRACT != 0
        val isStatic: Boolean get() = access and Opcodes.ACC_STATIC != 0
        val isNative: Boolean get() = access and Opcodes.ACC_NATIVE != 0
        val isPublic: Boolean get() = access and Opcodes.ACC_PUBLIC != 0
    }

    /** A class file reduced to what the synthesis decides on. */
    private class ClassInfo(
        val name: String,
        val access: Int,
        val superName: String?,
        val interfaces: List<String>,
        val methods: List<MethodInfo>,
        /** `(nested class, its outer)` pairs from the InnerClasses attribute. */
        val memberClasses: List<Pair<String, String>>
    ) {
        val isInterface: Boolean get() = access and Opcodes.ACC_INTERFACE != 0
    }

    private fun read(bytes: ByteArray): ClassInfo {
        var name = ""
        var access = 0
        var superName: String? = null
        val interfaces = mutableListOf<String>()
        val methods = mutableListOf<MethodInfo>()
        val members = mutableListOf<Pair<String, String>>()
        ClassReader(bytes).accept(object : ClassVisitor(Opcodes.ASM9) {
            override fun visit(
                version: Int,
                acc: Int,
                className: String,
                signature: String?,
                superName0: String?,
                interfaces0: Array<out String>?
            ) {
                name = className
                access = acc
                superName = superName0
                interfaces0?.forEach { interfaces += it }
            }

            override fun visitInnerClass(className: String, outerName: String?, innerName: String?, access: Int) {
                if (outerName != null) members += className to outerName
            }

            override fun visitMethod(
                acc: Int,
                methodName: String,
                descriptor: String,
                signature: String?,
                exceptions: Array<out String>?
            ): MethodVisitor {
                val body = mutableListOf<Insn>()
                methods += MethodInfo(acc, methodName, descriptor, signature, body)
                return object : MethodVisitor(Opcodes.ASM9) {
                    override fun visitInsn(opcode: Int) {
                        body += Insn(opcode, "")
                    }

                    override fun visitVarInsn(opcode: Int, variable: Int) {
                        body += Insn(opcode, variable.toString())
                    }

                    override fun visitTypeInsn(opcode: Int, type: String) {
                        body += Insn(opcode, type)
                    }

                    override fun visitMethodInsn(
                        opcode: Int,
                        owner0: String,
                        name0: String,
                        descriptor0: String,
                        isInterface: Boolean
                    ) {
                        body += Insn(opcode, "$owner0|$name0|$descriptor0|$isInterface")
                    }

                    // Everything else is outside what this pass can prove a delegation from; the
                    // sentinel can never match the classification's exact pattern.
                    fun unmodelled() {
                        body += Insn(UNMODELLED, "")
                    }

                    override fun visitFieldInsn(op: Int, owner0: String?, name0: String?, descriptor0: String?) = unmodelled()
                    override fun visitLdcInsn(value: Any?) = unmodelled()
                    override fun visitIntInsn(opcode: Int, operand: Int) = unmodelled()
                    override fun visitJumpInsn(opcode: Int, label: Label?) = unmodelled()
                    override fun visitIincInsn(variable: Int, increment: Int) = unmodelled()
                    override fun visitTableSwitchInsn(min: Int, max: Int, dflt: Label?, labels: Array<out Label>?) = unmodelled()
                    override fun visitLookupSwitchInsn(dflt: Label?, keys: IntArray?, labels: Array<out Label>?) = unmodelled()
                    override fun visitMultiANewArrayInsn(descriptor0: String?, dims: Int) = unmodelled()
                    override fun visitInvokeDynamicInsn(name0: String?, descriptor0: String?, bsm: Handle?, vararg bsmArgs: Any?) = unmodelled()
                    override fun visitTryCatchBlock(start: Label?, end: Label?, handler: Label?, type: String?) = unmodelled()
                }
            }
        }, ClassReader.SKIP_FRAMES)
        return ClassInfo(name, access, superName, interfaces, methods, members)
    }

    // -- resolving types -------------------------------------------------------------------------

    /**
     * The class files of a unit's hierarchy, resolved under the classes root and cached: every question
     * this pass asks — is `IFace` a direct superinterface, what does `m` resolve to there, is this
     * return type a subtype of that one — is answered from bytes, never from the generated text.
     */
    private class Types(private val root: Path) {

        private val cache = HashMap<String, ClassInfo?>()
        private val closures = HashMap<String, Map<String, ClassInfo?>>()
        private val subtypes = HashMap<String, Boolean>()

        fun load(path: Path): ClassInfo? =
            if (!Files.isRegularFile(path)) null else try {
                read(Files.readAllBytes(path))
            } catch (t: Throwable) {
                null
            }

        /** The class file of an internal name (`com/foo/Bar`) under the classes root, or null. */
        fun load(internalName: String): ClassInfo? = cache.getOrPut(internalName) {
            if (internalName.isEmpty() || internalName.startsWith("[")) return@getOrPut null
            load(root.resolve("$internalName.class"))
        }

        /** [internalName] and every supertype of it, transitively. Unresolvable types are absent. */
        fun closure(internalName: String): Map<String, ClassInfo?> = closures.getOrPut(internalName) {
            val out = LinkedHashMap<String, ClassInfo?>()
            collectSuperTypes(internalName, out)
            out
        }

        /** Just the supertypes of [info] — the declarations a class of it inherits. */
        fun superTypesOf(info: ClassInfo): Map<String, ClassInfo?> {
            val out = LinkedHashMap<String, ClassInfo?>()
            info.superName?.let { collectSuperTypes(it, out) }
            info.interfaces.forEach { collectSuperTypes(it, out) }
            return out
        }

        private fun collectSuperTypes(internalName: String, out: MutableMap<String, ClassInfo?>) {
            if (out.containsKey(internalName) || internalName.isEmpty() || internalName.startsWith("[")) return
            val info = load(internalName)
            out[internalName] = info
            if (info == null) return
            info.superName?.let { collectSuperTypes(it, out) }
            info.interfaces.forEach { collectSuperTypes(it, out) }
        }

        /**
         * True when the erased type [a] is provably a subtype of [b]; false when the hierarchy needed
         * to prove it is not in the classes root, which is a refusal, never an assumption.
         */
        fun isSubtype(a: String, b: String): Boolean {
            if (a == b) return true
            if (a.length == 1 || b.length == 1) return false
            if (a.startsWith("[") || b.startsWith("[")) {
                return a.startsWith("[") && b == "Ljava/lang/Object;"
            }
            if (b == "Ljava/lang/Object;") return true
            val internalA = a.removePrefix("L").removeSuffix(";")
            val internalB = b.removePrefix("L").removeSuffix(";")
            val key = "$internalA<:$internalB"
            subtypes[key]?.let { return it }
            val result = closure(internalA).keys.any { it == internalB }
            subtypes[key] = result
            return result
        }
    }

    // -- the delegation a candidate's body performs ----------------------------------------------

    /** Where a body delegates. */
    private class Delegation(
        /** `invokespecial IFace.m` — the interface's own default, rather than `IFace$DefaultImpls`. */
        val throughInterface: Boolean,
        val owner: String,
        val name: String,
        val descriptor: String,
        /** The descriptor type the body casts the call's result to, or null. */
        val cast: String?
    )

    /**
     * The delegation [method]'s body performs, or null when the body is not exactly
     * `aload_0; <one load per parameter>; <call>; [checkcast]; <return>`.
     */
    private fun delegationOf(method: MethodInfo): Delegation? {
        val insns = method.body
        if (insns.size < 3) return null
        if (insns[0].opcode != Opcodes.ALOAD || insns[0].operand != "0") return null

        var index = 1
        var slot = 1
        for (parameter in descriptorParameters(method.descriptor)) {
            if (index >= insns.size) return null
            if (insns[index].opcode != loadOpcode(parameter) || insns[index].operand != slot.toString()) return null
            index++
            slot += if (parameter == "J" || parameter == "D") 2 else 1
        }
        if (index >= insns.size) return null

        val call = insns[index]
        index++
        val throughInterface = when (call.opcode) {
            Opcodes.INVOKESPECIAL -> true
            Opcodes.INVOKESTATIC -> false
            else -> return null
        }
        val parts = call.operand.split('|')
        if (parts.size != 4) return null
        // `itf = false` on an invokespecial is a superclass bridge, which javac regenerates itself.
        if (throughInterface && parts[3] != "true") return null

        var cast: String? = null
        if (index < insns.size && insns[index].opcode == Opcodes.CHECKCAST) {
            cast = typeOfCheckcast(insns[index].operand)
            index++
        }
        if (index != insns.size - 1) return null

        val ours = method.descriptor.substringAfterLast(')')
        if (insns[index].opcode != returnOpcode(ours)) return null
        // The body must produce the method's own type: either the call already returns it, or it
        // returns the wider type the body then casts down to it.
        val produced = cast ?: parts[2].substringAfterLast(')')
        if (produced != ours) return null
        return Delegation(throughInterface, parts[0], parts[1], parts[2], cast)
    }

    /** The descriptor type a `checkcast` operand names: an internal name, or a descriptor for arrays. */
    private fun typeOfCheckcast(operand: String): String =
        if (operand.startsWith("[")) operand else "L$operand;"

    /** The opcode a load of a value of the erased descriptor type [parameter] uses. */
    private fun loadOpcode(parameter: String): Int = when (parameter.removePrefix("[")) {
        "J" -> Opcodes.LLOAD
        "D" -> Opcodes.DLOAD
        "F" -> Opcodes.FLOAD
        "Z", "B", "C", "S", "I" -> Opcodes.ILOAD
        else -> Opcodes.ALOAD
    }

    /** The opcode a return of a value of the erased descriptor type [erased] uses. */
    private fun returnOpcode(erased: String): Int = when (erased) {
        "V" -> Opcodes.RETURN
        "J" -> Opcodes.LRETURN
        "D" -> Opcodes.DRETURN
        "F" -> Opcodes.FRETURN
        "Z", "B", "C", "S", "I" -> Opcodes.IRETURN
        else -> Opcodes.ARETURN
    }

    // -- proving one candidate -------------------------------------------------------------------

    /** The Java source of the appending member for [method], or null when it cannot be proven. */
    private fun prove(owner: ClassInfo, method: MethodInfo, delegation: Delegation, types: Types): String? {
        if (!delegatesExactly(delegation, method)) return null
        val call = callFor(owner, method, delegation, types) ?: return null
        if (!satisfiesEveryDeclaration(owner, method, types)) return null
        val rendered = renderTypes(owner, method, types) ?: return null

        val arguments = rendered.parameters.indices.joinToString(", ") { "arg$it" }
        val invocation = buildString {
            append(call)
            append('(')
            if (!delegation.throughInterface) {
                append("this")
                if (arguments.isNotEmpty()) append(", ")
            }
            append(arguments)
            append(')')
        }
        val cast = delegation.cast?.takeIf { it != rendered.returnTypeErasure }
        val body = when {
            rendered.returnsVoid -> "$invocation;"
            cast != null -> "return (${renderErased(cast)}) $invocation;"
            else -> "return $invocation;"
        }
        val parameters = rendered.parameters.mapIndexed { index, type -> "$type arg$index" }.joinToString(", ")
        return buildString {
            append("   public ${rendered.returnType} ${method.name}($parameters) {\n")
            append("      $body\n")
            append("   }")
        }
    }

    /** The Java call the delegation must become, or null when its target cannot be resolved. */
    private fun callFor(owner: ClassInfo, method: MethodInfo, delegation: Delegation, types: Types): String? {
        if (delegation.throughInterface) {
            val iface = types.load(delegation.owner)
            if (iface != null && iface.isInterface && delegation.owner in owner.interfaces &&
                resolvesToDefault(delegation.owner, delegation, types)
            ) {
                return "${dotted(delegation.owner)}.super.${delegation.name}"
            }
            // An interface `super` call Java will not accept (an indirect superinterface, or a method
            // that is abstract there) still has the holder class, exactly as the older codegen used.
            return defaultImplsCall(delegation.owner, method, delegation, types)
        }
        return defaultImplsCall(delegation.owner.removeSuffix(SUFFIX), method, delegation, types)
    }

    /**
     * The Java call into `IFace$DefaultImpls`, or null when the holder is not there, does not declare
     * the static method the bytecode called, or the receiver cannot be passed to it.
     */
    private fun defaultImplsCall(
        iface: String,
        method: MethodInfo,
        delegation: Delegation,
        types: Types
    ): String? {
        val holder = types.load("$iface$SUFFIX") ?: return null
        if (holder.isInterface) return null
        val expected = "(L$iface;" + method.descriptor.substringAfter('(')
        val target = holder.methods.firstOrNull { it.name == delegation.name && it.descriptor == expected }
            ?: return null
        if (!target.isStatic || !target.isPublic) return null
        val ifaceInfo = types.load(iface) ?: return null
        val nested = ifaceInfo.memberClasses.any { (name, outer) -> name == holder.name && outer == iface }
        val spelled = if (nested) "${dotted(iface)}.DefaultImpls" else "${iface.replace('/', '.')}$SUFFIX"
        return "$spelled.${delegation.name}"
    }

    /** True when `name + descriptor`, as seen from [iface], is a non-abstract (default) method. */
    private fun resolvesToDefault(iface: String, delegation: Delegation, types: Types): Boolean {
        val own = types.load(iface)?.methods?.firstOrNull {
            it.name == delegation.name && it.descriptor == delegation.descriptor
        }
        if (own != null) return !own.isAbstract
        return types.closure(iface).values.filterNotNull().any { info ->
            info.methods.any {
                it.name == delegation.name && it.descriptor == delegation.descriptor && !it.isAbstract
            }
        }
    }

    /**
     * True when the delegation is the shape this method must carry: the target takes this method's own
     * parameters (interface call) or the receiver plus them (`DefaultImpls` call), and returns this
     * method's own return type, or the type the body casts from.
     */
    private fun delegatesExactly(delegation: Delegation, method: MethodInfo): Boolean {
        val returned = delegation.cast ?: method.descriptor.substringAfterLast(')')
        return if (delegation.throughInterface) {
            delegation.descriptor == parameterPart(method.descriptor) + returned
        } else {
            val iface = delegation.owner.removeSuffix(SUFFIX)
            delegation.descriptor == "(L$iface;" + method.descriptor.substringAfter('(').substringBefore(')') +
                ")" + returned
        }
    }

    /**
     * Rules 2-4: every same-signature declaration in the class's supertype closure accepts our return
     * type, at least one is abstract, and no class declares the signature.
     */
    private fun satisfiesEveryDeclaration(owner: ClassInfo, method: MethodInfo, types: Types): Boolean {
        val ours = method.descriptor.substringAfterLast(')')
        val parameters = parameterPart(method.descriptor)
        var sawAbstract = false
        for ((_, info) in types.superTypesOf(owner)) {
            if (info == null) continue
            if (!info.isInterface) return false
            for (declared in info.methods) {
                if (declared.name != method.name) continue
                if (parameterPart(declared.descriptor) != parameters) continue
                if (declared.isAbstract) sawAbstract = true
                if (!types.isSubtype(ours, declared.descriptor.substringAfterLast(')'))) return false
            }
        }
        return sawAbstract
    }

    /** Every declaration of the same signature (name and parameter part) in the class's closure. */
    private fun sameSignatureDeclarations(owner: ClassInfo, method: MethodInfo, types: Types): List<MethodInfo> {
        val parameters = parameterPart(method.descriptor)
        val out = mutableListOf<MethodInfo>()
        for ((_, info) in types.superTypesOf(owner)) {
            if (info == null) continue
            for (declared in info.methods) {
                if (declared.name == method.name && parameterPart(declared.descriptor) == parameters) out += declared
            }
        }
        return out
    }

    // -- rendering -------------------------------------------------------------------------------

    /** One rendered method: Java source for its types plus the erasures they were derived from. */
    private class Rendered(
        val parameters: List<String>,
        val returnType: String,
        val returnTypeErasure: String
    ) {
        val returnsVoid: Boolean get() = returnTypeErasure == "V"
    }

    /**
     * Renders the member's parameter and return types. The descriptor decides them; the method's
     * `Signature` is used for a position only when every same-signature declaration spells that type
     * exactly the same way, because a generic spelling the declarations do not share is what javac
     * refuses (invariant generics), while the erasure always overrides.
     *
     * A signature that is present but cannot be rendered — a type variable, an erasure that disagrees
     * with the descriptor — is a refusal: a member written from a different type than the compiler
     * recorded would be a guess.
     */
    private fun renderTypes(owner: ClassInfo, method: MethodInfo, types: Types): Rendered? {
        val erasedReturn = method.descriptor.substringAfterLast(')')
        val erasedParameters = descriptorParameters(method.descriptor)
        val erased = Rendered(
            erasedParameters.map { renderErased(it) },
            renderErased(erasedReturn),
            erasedReturn
        )
        if (method.signature == null) return erased

        val parsed = GenericSignature.parseMethod(method.signature) ?: return null
        if (parsed.parameterErasures != erasedParameters || parsed.returnErasure != erasedReturn) return null

        val declarations = sameSignatureDeclarations(owner, method, types)
        val parameters = parsed.parameters.mapIndexed { index, spelled ->
            if (declarations.isNotEmpty() && declarations.all { spellingOf(it, index) == spelled }) {
                spelled
            } else {
                erased.parameters[index]
            }
        }
        val returned = if (declarations.isNotEmpty() && declarations.all { spellingOf(it, null) == parsed.returnType }) {
            parsed.returnType
        } else {
            erased.returnType
        }
        return Rendered(parameters, returned, erasedReturn)
    }

    /** The rendered generic spelling of one of [declared]'s types, or null when it has none. */
    private fun spellingOf(declared: MethodInfo, parameterIndex: Int?): String? {
        val parsed = declared.signature?.let { GenericSignature.parseMethod(it) } ?: return null
        val parameters = descriptorParameters(declared.descriptor)
        if (parsed.parameterErasures != parameters) return null
        if (parsed.returnErasure != declared.descriptor.substringAfterLast(')')) return null
        return if (parameterIndex == null) parsed.returnType else parsed.parameters.getOrNull(parameterIndex)
    }

    /** Renders one erased descriptor type as Java source. */
    private fun renderErased(erased: String): String = when (erased) {
        "V" -> "void"
        "Z" -> "boolean"
        "B" -> "byte"
        "C" -> "char"
        "S" -> "short"
        "I" -> "int"
        "J" -> "long"
        "F" -> "float"
        "D" -> "double"
        else -> if (erased.startsWith("[")) {
            renderErased(erased.substring(1)) + "[]"
        } else {
            dotted(erased.removePrefix("L").removeSuffix(";"))
        }
    }

    /** A JVM internal name as Java source: `/` and a nested `$` both become `.`. */
    private fun dotted(internal: String): String = internal.replace('/', '.').replace('$', '.')

    /** The erased parameter types of a method descriptor, in order (`Lcom/foo/Bar;`, `[I`, `I`, ...). */
    private fun descriptorParameters(descriptor: String): List<String> {
        val out = mutableListOf<String>()
        var i = descriptor.indexOf('(') + 1
        while (i < descriptor.length && descriptor[i] != ')') {
            val start = i
            while (descriptor[i] == '[') i++
            i = if (descriptor[i] == 'L') descriptor.indexOf(';', i) + 1 else i + 1
            out += descriptor.substring(start, i)
        }
        return out
    }

    /** A descriptor's parameter part, `(...)` — the JVM's signature key for a method. */
    private fun parameterPart(descriptor: String): String =
        descriptor.substring(0, descriptor.indexOf(')') + 1)

    // -- "is it already declared?" ----------------------------------------------------------------

    /**
     * True when [text] declares a method named [name] taking [parameters]. The check is deliberately
     * generous — a nested class's declaration counts, and so does a call whose name happens to be
     * preceded by a type token — because a false "missing" would append a duplicate member, while a
     * false "declared" only leaves the unit to the gate, which is where it already is.
     */
    private fun declaresMethod(text: String, name: String, parameters: List<String>): Boolean {
        val tokens = JavaText.tokenize(text)
        val wanted = parameters.map { simpleNameOf(it) }
        for (i in tokens.indices) {
            if (tokens[i].kind != JavaText.Kind.IDENT || tokens[i].text != name) continue
            val before = JavaText.previousSignificant(tokens, i) ?: continue
            if (!isTypeToken(tokens, before)) continue
            val open = JavaText.nextSignificant(tokens, i + 1) ?: continue
            if (tokens[open].text != "(") continue
            val close = JavaText.matchingParen(tokens, open) ?: continue
            val segments = topLevelSegments(tokens, open, close)
            if (segments.size != parameters.size) continue
            if (segments.withIndex().all { (index, range) -> typeNamesIn(tokens, range).contains(wanted[index]) }) {
                return true
            }
        }
        return false
    }

    /** Every identifier a parameter's tokens could be spelling its type with. */
    private fun typeNamesIn(tokens: List<JavaText.Token>, range: IntRange): Set<String> {
        val names = mutableSetOf<String>()
        // The last identifier of a parameter is its own name, never part of its type.
        val spellable = range.filter { tokens[it].kind != JavaText.Kind.COMMENT }.dropLast(1)
        for (index in spellable) {
            if (tokens[index].kind == JavaText.Kind.IDENT) names += tokens[index].text
        }
        return names
    }

    /** The token ranges of a parameter list's top-level comma-separated segments. */
    private fun topLevelSegments(tokens: List<JavaText.Token>, open: Int, close: Int): List<IntRange> {
        val out = mutableListOf<IntRange>()
        var depth = 0
        var start = open + 1
        for (i in (open + 1) until close) {
            when (tokens[i].text) {
                "(", "[", "<" -> depth++
                ")", "]", ">" -> depth--
                "," -> if (depth == 0) {
                    if (i > start) out += start until i
                    start = i + 1
                }
            }
        }
        if (close > start) out += start until close
        return out
    }

    /** The simple name a declaration would spell an erased descriptor type with. */
    private fun simpleNameOf(erased: String): String =
        if (erased.startsWith("[")) {
            simpleNameOf(erased.substring(1))
        } else if (erased.length == 1) {
            renderErased(erased)
        } else {
            erased.removePrefix("L").removeSuffix(";").substringAfterLast('/').substringAfterLast('$')
        }

    private fun isTypeToken(tokens: List<JavaText.Token>, index: Int): Boolean {
        val token = tokens[index]
        return (token.kind == JavaText.Kind.IDENT && !JavaText.isKeyword(token.text)) ||
            token.text == ">" || token.text == "]"
    }

    /** A generic method signature as Java source, or null when it cannot be rendered. */
    private object GenericSignature {

        class Parsed(
            val parameters: List<String>,
            val parameterErasures: List<String>,
            val returnType: String,
            val returnErasure: String
        )

        fun parseMethod(signature: String): Parsed? = try {
            val cursor = Cursor(signature)
            if (cursor.peek() == '<') return null // a generic method: needs its own type parameters
            if (cursor.peek() != '(') return null
            cursor.next()
            val parameters = mutableListOf<String>()
            val erasures = mutableListOf<String>()
            while (cursor.peek() != ')') {
                val type = cursor.type() ?: return null
                parameters += type.text
                erasures += type.erased
            }
            cursor.next()
            if (cursor.peek() == 'V') {
                cursor.next()
                if (cursor.hasNext()) return null
                Parsed(parameters, erasures, "void", "V")
            } else {
                val returned = cursor.type() ?: return null
                if (cursor.hasNext()) return null
                Parsed(parameters, erasures, returned.text, returned.erased)
            }
        } catch (t: Throwable) {
            null
        }

        private class Type(val text: String, val erased: String)

        private class Cursor(private val text: String) {
            private var index = 0

            fun hasNext(): Boolean = index < text.length
            fun peek(): Char = text[index]
            fun next(): Char = text[index++]

            fun type(): Type? {
                if (index >= text.length) return null
                return when (val c = text[index]) {
                    '[' -> {
                        index++
                        val element = type() ?: return null
                        Type("${element.text}[]", "[${element.erased}")
                    }
                    'Z', 'B', 'C', 'S', 'I', 'J', 'F', 'D' -> {
                        index++
                        Type(primitive(c), c.toString())
                    }
                    'L' -> classType()
                    else -> null // 'T<name>;' (a type variable) and anything unknown are refusals
                }
            }

            private fun primitive(c: Char): String = when (c) {
                'Z' -> "boolean"
                'B' -> "byte"
                'C' -> "char"
                'S' -> "short"
                'I' -> "int"
                'J' -> "long"
                'F' -> "float"
                'D' -> "double"
                else -> "void"
            }

            private fun classType(): Type? {
                index++ // 'L'
                val name = StringBuilder()
                while (index < text.length && text[index] != ';' && text[index] != '<') name.append(text[index++])
                val arguments = mutableListOf<String>()
                if (index < text.length && text[index] == '<') {
                    index++
                    while (index < text.length && text[index] != '>') {
                        arguments += when (text[index]) {
                            '+' -> {
                                index++
                                "? extends ${(type() ?: return null).text}"
                            }
                            '-' -> {
                                index++
                                "? super ${(type() ?: return null).text}"
                            }
                            '*' -> {
                                index++
                                "?"
                            }
                            else -> (type() ?: return null).text
                        }
                    }
                    if (index >= text.length) return null
                    index++ // '>'
                }
                if (index >= text.length || text[index] != ';') return null
                index++
                val internal = name.toString()
                if (internal.isEmpty()) return null
                val spelled = dotted(internal)
                val rendered = if (arguments.isEmpty()) spelled else "$spelled<${arguments.joinToString(", ")}>"
                return Type(rendered, "L$internal;")
            }
        }
    }
}
