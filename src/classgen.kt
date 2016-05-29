import com.jtransc.error.noImpl
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes
import org.objectweb.asm.Opcodes.*
import java.lang.reflect.Method
import java.net.URL
import java.net.URLClassLoader

object ClassGen {
	fun generate(program: Program) = Impl().apply { this.visit(program) }.cw.toByteArray()

	private class Impl : ProgramVisitor() {
		val ALLOCA = MethodRef(LlvmRuntime::class.java.name, LlvmRuntime::alloca.name, "(I)I")
		val LI32 = MethodRef(LlvmRuntime::class.java.name, LlvmRuntime::li32.name, "(I)I")
		val SI32 = MethodRef(LlvmRuntime::class.java.name, LlvmRuntime::si32.name, "(II)V")
		val SP = FieldRef(LlvmRuntime::class.java.name, LlvmRuntime::SP.name, "I")
		val LocalSP = LOCAL("%SP")

		lateinit var program: Program
		lateinit var cw: ClassWriter
		lateinit var mv: MethodVisitor
		lateinit var function: Decl.DECFUN
		val locals = hashMapOf<LOCAL, Int>()
		var localCount = 0
		var maxStackCount = 0

		override fun visit(program: Program) {
			this.program = program
			cw = ClassWriter(0)
			cw.visit(
				49,
				ACC_PUBLIC + ACC_SUPER,
				program.className,
				null,
				"java/lang/Object",
				null
			)

			//cw.visitSource("Hello.java", null);

			mv = cw.visitMethod(ACC_PUBLIC, "<init>", "()V", null, null)
			mv.visitVarInsn(ALOAD, 0)
			mv.visitMethodInsn(INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false)
			mv.visitInsn(RETURN)
			mv.visitMaxs(1, 1)
			mv.visitEnd()

			super.visit(program)

			cw.visitEnd()
		}

		// @TODO: Cache descriptor!
		fun Decl.DECFUN.getFunDescriptor(): String {
			val args = this.args.map { it.type.toJavaType() }.joinToString("")
			val rettype = this.type.toJavaType()
			return "($args)$rettype"
		}

		// @TODO: Create a lookup table!
		fun Program.getFun(ref: Reference): Decl.DECFUN {
			return this.decls.filterIsInstance<Decl.DECFUN>().first { it.name == ref }
		}

		override fun visit(decl: Decl.DECFUN) {
			this.function = decl

			mv = cw.visitMethod(ACC_STATIC or ACC_PUBLIC, (decl.name as GLOBAL).id, decl.getFunDescriptor(), null, null)
			locals.clear()
			localCount = 0
			maxStackCount = 10

			for (arg in decl.args) getLocalId(arg.name)

			mv.GETSTATIC(SP)
			_store(Type.INT32, LocalSP)

			super.visit(decl)

			mv.visitMaxs(maxStackCount, localCount)
			mv.visitEnd()
		}

		private fun getLocalId(local: LOCAL): Int = locals.getOrPut(local) { localCount++ }

		override fun visit(stm: Stm.ALLOCA) {
			mv.INT(4)
			mv.INVOKESTATIC(ALLOCA)
			mv.visitVarInsn(Opcodes.ISTORE, getLocalId(stm.target as LOCAL))
		}

		override fun visit(typedValue: TypedValue) {
			val value = typedValue.value
			when (value) {
				is INT -> mv.INT(value.value)
				is LOCAL -> _load(typedValue.type, value)
				else -> noImpl("value: $value")
			}
		}

		override fun visit(stm: Stm.STORE) {
			visit(stm.dst)
			visit(stm.src)
			mv.INVOKESTATIC(SI32)
		}

		override fun visit(stm: Stm.LOAD) {
			visit(stm.from)
			when (stm.targetType) {
				Type.INT32 -> mv.INVOKESTATIC(LI32)
				else -> {
					noImpl("${stm.targetType}")
				}
			}
			mv.visitVarInsn(Opcodes.ISTORE, getLocalId(stm.target as LOCAL))
		}

		override fun visit(stm: Stm.BINOP) {
			visit(stm.typedLeft)
			visit(stm.typedRight)
			when (stm.op) {
				"add" -> mv.ADD(stm.type)
				"sub" -> mv.SUB(stm.type)
				"mul" -> mv.MUL(stm.type)
				"sdiv" -> mv.DIV(stm.type)
				else -> noImpl("Unsupported op ${stm.op}")
			}
			_store(stm.type, stm.target)
		}

		fun _store(type: Type, local: LOCAL) {
			when (type) {
				Type.INT32, is Type.PTR -> {
					mv.visitVarInsn(Opcodes.ISTORE, getLocalId(local))
				}
				else -> noImpl("${type}")
			}
		}

		fun _load(type: Type, local: LOCAL) {
			when (type) {
				Type.INT32, is Type.PTR -> {
					mv.visitVarInsn(Opcodes.ILOAD, getLocalId(local))
				}
				else -> noImpl("${type}")
			}
		}

		override fun visit(stm: Stm.CALL) {
			for (arg in stm.args) {
				visit(arg)
			}
			val func = program.getFun(stm.name)
			val name = func.name
			val desc = func.getFunDescriptor()
			mv.visitMethodInsn(Opcodes.INVOKESTATIC, program.className, name.id, desc, false)
			_store(func.type, stm.target)
		}

		override fun visit(stm: Stm.RET) {

			_load(Type.INT32, LocalSP)
			mv.PUTSTATIC(SP)

			//println("ret!")
			visit(stm.typedValue)
			mv.vreturn(this.function.type)
		}
	}
}

class MethodRef(val owner: String, val name:String, val desc:String)
class FieldRef(val owner: String, val name:String, val desc:String)

fun MethodVisitor.ADD(type: Type) = when (type) {
	Type.INT32 -> this.visitInsn(Opcodes.IADD)
	else -> noImpl("${type}")
}

fun MethodVisitor.SUB(type: Type) = when (type) {
	Type.INT32 -> this.visitInsn(Opcodes.ISUB)
	else -> noImpl("${type}")
}

fun MethodVisitor.MUL(type: Type) = when (type) {
	Type.INT32 -> this.visitInsn(Opcodes.IMUL)
	else -> noImpl("${type}")
}

fun MethodVisitor.DIV(type: Type) = when (type) {
	Type.INT32 -> this.visitInsn(Opcodes.IDIV)
	else -> noImpl("${type}")
}

inline fun <reified T: Any> MethodVisitor.NEWARRAY() {
	NEWARRAY(T::class.java)
}

private fun MethodVisitor._FIELD(opcode:Int, field: FieldRef) {
	visitFieldInsn(opcode, field.owner, field.name, field.desc)
}

fun MethodVisitor.GETSTATIC(field: FieldRef) = _FIELD(Opcodes.GETSTATIC, field)
fun MethodVisitor.PUTSTATIC(field: FieldRef) = _FIELD(Opcodes.PUTSTATIC, field)

fun MethodVisitor.INVOKESTATIC(method: MethodRef) {
	visitMethodInsn(Opcodes.INVOKESTATIC, method.owner, method.name, method.desc, false)
}

fun MethodVisitor.NEWARRAY(type: Class<*>) {
	when (type) {
		java.lang.Integer::class.java -> visitIntInsn(Opcodes.NEWARRAY, Opcodes.T_INT)
		else -> noImpl("$type")
	}
}

fun MethodVisitor.INT(value: Int) {
	when (value) {
		-1 -> visitInsn(ICONST_M1)
		0 -> visitInsn(ICONST_0)
		1 -> visitInsn(ICONST_1)
		2 -> visitInsn(ICONST_2)
		3 -> visitInsn(ICONST_3)
		4 -> visitInsn(ICONST_4)
		5 -> visitInsn(ICONST_5)
		in Byte.MIN_VALUE .. Byte.MAX_VALUE -> visitIntInsn(Opcodes.BIPUSH, value)
		in Short.MIN_VALUE .. Short.MAX_VALUE -> visitIntInsn(Opcodes.SIPUSH, value)
		else -> {
			visitLdcInsn(value)
		}
	}
}

inline fun <reified T: Any> MethodVisitor.RETURN() = this.vreturn(T::class.java)

fun MethodVisitor.vreturn(clazz: Class<*>) = clazz.toType()

fun Class<*>.toType() = when (this) {
	java.lang.Void::class.java -> Type.VOID
	java.lang.Integer::class.java -> Type.INT32
	else -> noImpl("$this")
}

fun MethodVisitor.vreturn(type: Type) {
	when (type) {
		Type.VOID -> visitInsn(RETURN)
		Type.INT32 -> visitInsn(IRETURN)
		else ->visitInsn(ARETURN)
	}
}

fun getClassFromByteArray(name: String, ba: ByteArray): Class<*> {
	return ByteClassLoader(arrayOf(), LlvmRuntime::class.java.classLoader, hashMapOf(name to ba)).loadClass(name)
}

open class ByteClassLoader(
	urls: Array<URL>,
	parent: ClassLoader?,
	val extraClassDefs: MutableMap<String, ByteArray>
) : URLClassLoader(urls, parent) {
	override fun findClass(name: String): Class<*> {
		val classBytes = this.extraClassDefs.remove(name);
		if (classBytes != null) {
			return defineClass(name, classBytes, 0, classBytes.size);
		}
		return super.findClass(name);
	}
}
