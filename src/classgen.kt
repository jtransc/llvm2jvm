import com.jtransc.error.invalidOp
import com.jtransc.error.noImpl
import com.jtransc.mem.BytesWrite
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes
import org.objectweb.asm.Opcodes.*
import java.io.ByteArrayOutputStream
import java.lang.reflect.Method
import java.net.URL
import java.net.URLClassLoader
import java.util.*

// https://docs.oracle.com/javase/specs/jvms/se7/html/jvms-4.html
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

			createField(Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC or Opcodes.ACC_FINAL, "STATIC_DATA", "Ljava/lang/String;", heapWriter.getDataAsString(GLOBALS))

			mv = cw.visitMethod(ACC_PUBLIC, "<clinit>", "()V", null, null)
			mv.visitFieldInsn(GETSTATIC, program.className, "STATIC_DATA", "Ljava/lang/String;")
			mv.visitMethodInsn(INVOKESTATIC, LlvmRuntime::class.java.name, LlvmRuntime::initStaticData.name, "(Ljava/lang/String;)V", false)
			mv.visitInsn(RETURN)
			mv.visitMaxs(1, 1)
			mv.visitEnd()

			cw.visitEnd()
		}

		class GlobalInfo(val basename:String, val id: Int, val address: Int) {
			//val name = "global$id"
			val name = if (basename.startsWith("\\")) "_global$id" else basename
		}

		var globalId = 0
		val GLOBALS = hashMapOf<String, GlobalInfo>()

		class HeapWriter {
			val data = ByteArrayOutputStream()
			val fixes = IdentityHashMap<Value, Int>()

			fun writeBytes(value: Value): Int {
				val ptr = data.size()
				fixes[value] = ptr
				when (value) {
					is I8ARRAY -> {
						data.write(value.bytes)

					}
					is GETELEMENTPTR -> {
						data.write(ByteArray(4))
					}
					else -> invalidOp("Don't know how to store $value")
				}
				return ptr
			}

			fun getDataAsString(GLOBALS: Map<String, GlobalInfo>): String {
				val bytes = data.toByteArray()

				fun fixPointers() {
					fun getAddress(value: Value): Int {
						//when ()
						return when (value) {
							is GETELEMENTPTR -> {
								getAddress(value.value.value)
							}
							is GLOBAL -> {
								GLOBALS[value.id]!!.address
							}
							else -> invalidOp("getDataAsString.fixPointers: Not supported $value")
						}
					}

					for ((value, offset) in fixes) {
						when (value) {
							is GETELEMENTPTR -> {
								val fixedPointer = getAddress(value)
								BytesWrite.writeIntLE(bytes, offset, fixedPointer)
								//println("Fixed pointer: $offset -> $fixedPointer")
							}
						}
					}
				}

				fun bytesToString(): String {
					var out = ""
					for (c in bytes) out += c.toChar()
					return out
				}

				fixPointers()
				return bytesToString()
			}
		}

		val heapWriter = HeapWriter()

		private fun createField(acc: Int, name: String, desc: String, default: Any?) {
			cw.visitField(acc, name, desc, null, default).visitEnd()
		}

		override fun visit(decl: Decl.DECVAR) {
			val address = heapWriter.writeBytes(decl.value)
			val info = GlobalInfo(decl.id, globalId++, address)
			GLOBALS[decl.id] = info
			createField(Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC or Opcodes.ACC_FINAL, info.name, "I", address)
		}

		// @TODO: Cache descriptor!
		fun Decl.DECLARE_BASE.getFunDescriptor(): String {
			val args = this.argTypes.map { it.toJavaType() }.joinToString("")
			val rettype = this.type.toJavaType()
			return "($args)$rettype"
		}

		// @TODO: Create a lookup table!
		fun Program.getFun(ref: Reference): Decl.DECLARE_BASE {
			return this.decls.filterIsInstance<Decl.DECLARE_BASE>().firstOrNull { it.name.id == ref.id } ?: invalidOp("Cannot find function declaration $ref")
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
				is GLOBAL -> _load(typedValue.type, value)
				is GETELEMENTPTR -> {
					// @TODO: indices!
					visit(value.value)
				}
				else -> noImpl("value: $value")
			}
		}

		override fun visit(stm: Stm.STORE) {
			visit(stm.dst)
			visit(stm.src)
			mv.INVOKESTATIC(SI32)
		}

		override fun visit(stm: Stm.LABEL) {
			//visit(stm.dst)
			//visit(stm.src)
			//mv.visitLabel()
			//mv.INVOKESTATIC(SI32)
		}

		override fun visit(stm: Stm.LOAD) {
			visit(stm.from)
			when (stm.targetType) {
				Type.INT32 -> mv.INVOKESTATIC(LI32)
				is Type.PTR -> mv.INVOKESTATIC(LI32)
				else -> {
					noImpl("Stm.LOAD: ${stm.targetType}")
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

		fun getVarDecl(id: String): Decl.DECVAR {
			return program.decls.filterIsInstance<Decl.DECVAR>().firstOrNull { it.id == id } ?: invalidOp("Cannot find global $id")
		}

		fun _load(type: Type, global: GLOBAL) {
			val info = GLOBALS[global.id] ?: invalidOp("Cannot find global ${global.id}")
			mv.visitFieldInsn(Opcodes.GETSTATIC, program.className, info.name, getVarDecl(global.id).type.toJavaType())
		}

		override fun visit(stm: Stm.CALL) {
			val func = program.getFun(stm.name)
			val name = func.name
			val desc = func.getFunDescriptor()
			for ((arg, type) in stm.args.zip(func.argTypes)) {
				visit(arg)
				//if (arg.type != type) {
				//	mv.visitTypeInsn(Opcodes.CHECKCAST, type.toJavaType())
				//}
			}
			when (name.id) {
				// builtins!
				"puts" -> {
					//println("puts: $desc")
					mv.visitMethodInsn(Opcodes.INVOKESTATIC, LlvmRuntime::class.java.name, name.id, desc, false)
				}
				else -> {
					mv.visitMethodInsn(Opcodes.INVOKESTATIC, program.className, name.id, desc, false)
				}
			}
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
