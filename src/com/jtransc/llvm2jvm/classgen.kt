package com.jtransc.llvm2jvm

import com.jtransc.error.invalidOp
import com.jtransc.error.noImpl
import com.jtransc.mem.BytesWrite
import com.jtransc.text.TokenReader
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Label
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes
import org.objectweb.asm.Opcodes.*
import java.io.ByteArrayOutputStream
import java.net.URL
import java.net.URLClassLoader
import java.util.*
import kotlin.reflect.KCallable

val Class<*>.internalName: String get() = this.canonicalName.replace('.', '/')
val KCallable<*>.internalName: String get() = this.name

// https://docs.oracle.com/javase/specs/jvms/se7/html/jvms-4.html
object ClassGen {
	fun generate(program: Program) = Impl().apply { this.visit(program) }.cw.toByteArray()

	private class Impl : ProgramVisitor() {
		val ALLOCA = MethodRef(LlvmRuntime::class.java.internalName, LlvmRuntime::alloca.internalName, "(I)I")
		val LI32 = MethodRef(LlvmRuntime::class.java.internalName, LlvmRuntime::li32.internalName, "(I)I")
		val LI64 = MethodRef(LlvmRuntime::class.java.internalName, LlvmRuntime::li64.internalName, "(J)I")
		val SI32 = MethodRef(LlvmRuntime::class.java.internalName, LlvmRuntime::si32.internalName, "(II)V")
		val SP = FieldRef(LlvmRuntime::class.java.internalName, LlvmRuntime::SP.internalName, "I")
		val LocalSP = LOCAL("%SP")

		lateinit var program: Program
		lateinit var cw: ClassWriter
		lateinit var mv: MethodVisitor
		lateinit var function: Decl.DECFUN
		val locals = hashMapOf<LOCAL, Int>()
		var localCount = 0
		var maxStackCount = 0

		val structsByName = hashMapOf<String, Type.STRUCT>()

		override fun visit(program: Program) {
			this.program = program

			for (dectype in program.decls.filterIsInstance<Decl.DECTYPE>()) {
				structsByName[dectype.id] = dectype.type as Type.STRUCT
			}

			//cw = ClassWriter(ClassWriter.COMPUTE_FRAMES or ClassWriter.COMPUTE_MAXS)
			cw = ClassWriter(ClassWriter.COMPUTE_FRAMES)
			cw.visit(50, ACC_PUBLIC + ACC_SUPER, program.className, null, "java/lang/Object", null)

			//cw.visitSource("Hello.java", null);

			mv = cw.visitMethod(ACC_PUBLIC, "<init>", "()V", null, null)
			mv.visitVarInsn(ALOAD, 0)
			mv.visitMethodInsn(INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false)
			mv.visitInsn(RETURN)
			mv.visitMaxs(1, 1)
			mv.visitMaxs(1, 1)
			mv.visitEnd()

			super.visit(program)

			createField(Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC or Opcodes.ACC_FINAL, "STATIC_DATA", "Ljava/lang/String;", heapWriter.getDataAsString(GLOBALS))

			mv = cw.visitMethod(ACC_PUBLIC, "<clinit>", "()V", null, null)
			mv.visitFieldInsn(GETSTATIC, program.className, "STATIC_DATA", "Ljava/lang/String;")
			mv.visitMethodInsn(INVOKESTATIC, LlvmRuntime::class.java.internalName, LlvmRuntime::initStaticData.internalName, "(Ljava/lang/String;)V", false)
			mv.visitInsn(RETURN)
			mv.visitMaxs(1, 1)
			mv.visitEnd()

			mv = cw.visitMethod(ACC_STATIC or ACC_PUBLIC, "main", "([Ljava/lang/String;)V", null, null)
			mv.visitLdcInsn(getJavaObjectType(program.className))
			mv.visitMethodInsn(INVOKESTATIC, LlvmRuntime::class.java.internalName, LlvmRuntime::mainBootstrap.internalName, "(Ljava/lang/Class;)V", false)
			mv.visitInsn(RETURN)
			mv.visitMaxs(1, 1)
			mv.visitEnd()
			cw.visitEnd()
		}

		private fun getJavaObjectType(name: String): org.objectweb.asm.Type {
			return org.objectweb.asm.Type.getObjectType(name)
		}

		class GlobalInfo(val basename: String, val id: Int, val address: Int) {
			//val name = "global$id"
			val name = if (basename.startsWith("\\") || basename.contains('.')) "_global$id" else basename
		}

		var globalId = 0
		var tempId = 0
		val GLOBALS = hashMapOf<String, GlobalInfo>()

		class HeapWriter {
			val data = ByteArrayOutputStream()
			val fixes = IdentityHashMap<Value, Int>()

			fun writeBytes(value: TypedValue): Int {
				return writeBytes(value.value) // @TODO: Use this information!
			}

			val temp = ByteArray(8)

			fun writeBytes(value: Value): Int {
				val ptr = data.size()
				fixes[value] = ptr
				when (value) {
					is I8ARRAY -> data.write(value.bytes)
					is GETELEMENTPTR -> data.write(ByteArray(4))
					is INT -> {
						BytesWrite.writeIntLE(temp, 0, value.value)
						data.write(temp, 0, 4)
					}
					is LONG -> {
						BytesWrite.writeLongLE(temp, 0, value.value)
						data.write(temp, 0, 8)
					}
					is GENERICARRAY -> {
						for (v in value.values) writeBytes(v)
					}
					is GENERICSTRUCT -> {
						for (v in value.values) writeBytes(v)
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
			// @TODO: This is not fine! We have to decide whether we are going to store in the STATIC area or in fields
			val address = heapWriter.writeBytes(decl.value)
			val info = GlobalInfo(decl.id, globalId++, address)
			GLOBALS[decl.id] = info
			val type = decl.type
			val typeStr = when (type) {
				Type.INT64 -> "J"
				else -> "I"
			}
			val constant: Any = when (type) {
				Type.INT64 -> address.toLong()
				else -> address.toInt()
			}
			createField(Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC or Opcodes.ACC_FINAL, info.name, typeStr, constant)
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

		var preserveSP = false
		var phiValues = hashMapOf<Pair<String, String>, TypedValue>()
		var currentLabel = "-"

		fun startFunction(decl: Decl.DECFUN) {
			this.function = decl
			// @TODO: This should be unnecessary if there would be a state created per function
			this.labels.clear()
			locals.clear()
			localCount = 0
			maxStackCount = 10
			this.currentLabel = "-"
			phiValues.clear()

			for (arg in decl.args) getLocalId(arg.type, arg.local)

			this.preserveSP = decl.body.stms.any { it is Stm.ALLOCA }

			// Get PHIs stuff
			var currentLabel = "-"
			for (stm in decl.body.stms) {
				when (stm) {
					is Stm.LABEL -> {
						currentLabel = stm.name
					}
					is Stm.PHI -> {
						for ((fromLabel, value) in stm.labelsToValues) {
							phiValues[Pair(fromLabel, currentLabel)] = value
						}
					}
				}
			}
		}

		fun normalizeMethodName(basename: String): String {
			return basename.replace('.', '_')
		}

		override fun visit(decl: Decl.DECFUN) {
			startFunction(decl)

			mv = cw.visitMethod(ACC_STATIC or ACC_PUBLIC, normalizeMethodName((decl.name as GLOBAL).id), decl.getFunDescriptor(), null, null)

			if (preserveSP) {
				mv.GETSTATIC(SP)
				_store(Type.INT32, LocalSP)
			}

			super.visit(decl)

			try {
				mv.visitMaxs(maxStackCount, localCount)
			} catch (e: ArrayIndexOutOfBoundsException) {
				println("Error calculating maxs for function: ${function.name}")
				e.printStackTrace()
			}
			//mv.visitMaxs(1, 1)
			mv.visitEnd()
		}

		private fun getLocalId(type: Type, local: LOCAL): Int {
			return locals.getOrPut(local) {
				val local = localCount
				if (type.isLongOrDouble()) {
					localCount += 2
				} else {
					localCount++
				}
				local
			}
		}

		fun storeToLocal(type: Type, local: LOCAL) {
			mv.visitVarInsn(Opcodes.ISTORE, getLocalId(type, local))
		}

		override fun visit(stm: Stm.ALLOCA) {
			mv.INT(4)
			mv.INVOKESTATIC(ALLOCA)
			storeToLocal(Type.INT32, stm.target)
		}

		fun Value.getConstantValue(): Any? = when (this) {
			is INT -> this.value
		//else -> invalidOp("unsupported!")
			else -> null
		}

		fun TypedValue.getConstantValue(): Any? = this.value.getConstantValue()

		fun Type.getResolvedType(): Type {
			if (this is Type.STRUCT_REF) {
				return structsByName[this.name] ?: invalidOp("Can't find struct with name: $this")
			} else {
				return this
			}
		}

		fun Type.getSizeInBytesFixed(): Int = this.getResolvedType().getSizeInBytes()

		override fun visit(typedValue: TypedValue) {
			val value = typedValue.value
			when (value) {
				NULL -> mv.INT(0)
				is INT -> mv.INT(value.value)
				is LONG -> mv.LONG(value.value)
				is BOOL -> mv.INT(if (value.value) 1 else 0)
				is LOCAL -> _load(typedValue.type, value)
				is GLOBAL -> _load(typedValue.type, value)
				is GETELEMENTPTR -> {
					// @TODO: indices!
					visit(value.value)
					val idx2 = value.offsets.last()
					val indxConstant = idx2.getConstantValue()

					val type = value.type1.getResolvedType()

					val offset = if (type is Type.STRUCT) {
						type.getOffsetInBytes(indxConstant as Int)
					} else {
						0
					}

					val mult = if (type is Type.ARRAY && (type.type.getSizeInBytesFixed() != 1)) {
						type.type.getSizeInBytesFixed()
					} else {
						1
					}

					if (offset != 0) {
						val add = offset
						if (add != 0) {
							mv.INT(add)
							mv.visitInsn(Opcodes.IADD)
						}
					} else if (indxConstant != null) {
						val add = (indxConstant as Int) * mult
						if (add != 0) {
							mv.INT(add)
							mv.visitInsn(Opcodes.IADD)
						}
					} else {
						visit(idx2)
						conv(idx2.type, Type.INT32)
						if (mult != 1) {
							mv.INT(mult)
							mv.visitInsn(Opcodes.IMUL)
						}
						mv.visitInsn(Opcodes.IADD)
					}
				}
				is BITCAST -> {
					// @TODO: Proper BITCAST
					visit(TypedValue(value.fromType, value.ref))
				}
				else -> noImpl("value: $value")
			}
		}

		override fun visit(stm: Stm.STORE) {
			visit(stm.dst)
			visit(stm.src)
			mv.INVOKESTATIC(SI32)
		}

		override fun visit(stm: Stm.ASSIGN) {
			visit(stm.src)
			storeToLocal(stm.src.type, stm.target)
		}

		val labels = hashMapOf<String, Label>()

		fun getLabelByName(name: String): Label = labels.getOrPut(name) { Label() }

		override fun visit(stm: Stm.LABEL) {
			currentLabel = stm.name
			mv.visitLabel(getLabelByName(stm.name))
		}

		override fun visit(stm: Stm.LOAD) {
			visit(stm.from)
			when (stm.targetType) {
				Type.INT32, is Type.PTR -> mv.INVOKESTATIC(LI32)
				Type.INT64 -> mv.INVOKESTATIC(LI64)
				else -> {
					noImpl("com.jtransc.llvm2jvm.Stm.LOAD: ${stm.targetType}")
				}
			}
			storeToLocal(stm.targetType, stm.target)
		}

		override fun visit(stm: Stm.BINOP) {
			visit(stm.typedLeft)
			visit(stm.typedRight)
			when (stm.op) {
				"or" -> mv.OR(stm.type)
				"add" -> mv.ADD(stm.type)
				"sub" -> mv.SUB(stm.type)
				"mul" -> mv.MUL(stm.type)
				"sdiv" -> mv.DIV(stm.type)
				"slt" -> mv.SLT(stm.type)
				"sgt" -> mv.SGT(stm.type)
				"ult" -> mv.ULT(stm.type)
				else -> noImpl("Unsupported op ${stm.op}")
			}
			_store(stm.type, stm.target)
		}

		fun _store(type: Type, local: LOCAL) = storeToLocal(type, local)

		fun _load(type: Type, local: LOCAL) {
			when (type) {
				Type.INT1, Type.INT32, is Type.PTR -> {
					mv.visitVarInsn(Opcodes.ILOAD, getLocalId(type, local))
				}
				Type.INT64 -> {
					mv.visitVarInsn(Opcodes.LLOAD, getLocalId(type, local))
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

			val argReader = TokenReader<TypedValue>(stm.args)

			for (type in func.argTypes) {
				if (type is Type.VARARG) {
					val varargCount = argReader.size - argReader.position
					val local = getLocalId(Type.ARRAY(Type.INT32, varargCount), LOCAL("temp_${tempId++}"))

					mv.INT(varargCount)
					mv.visitIntInsn(Opcodes.NEWARRAY, Opcodes.T_INT)
					mv.visitVarInsn(Opcodes.ASTORE, local)
					var n = 0
					while (argReader.hasMore) {
						mv.visitVarInsn(Opcodes.ALOAD, local)
						mv.INT(n++)
						visit(argReader.read())
						mv.visitInsn(Opcodes.IASTORE)
					}
					mv.visitVarInsn(Opcodes.ALOAD, local)
					//mv.visitTypeInsn(Opcodes.CHECKCAST, "[Ljava/lang/Object;")
				} else {
					val arg = argReader.read()
					visit(arg)
					conv(arg.type, type)
				}
			}

			// Intrinsics
			if (name.id.startsWith("llvm.")) {
				when (name.id) {
					"llvm.va_start" -> {
						mv.visitMethodInsn(Opcodes.INVOKESTATIC, LlvmRuntime::class.java.internalName, LlvmRuntime::llvm_va_start.internalName, desc, false)
					}
					"llvm.va_end" -> {
						mv.visitMethodInsn(Opcodes.INVOKESTATIC, LlvmRuntime::class.java.internalName, LlvmRuntime::llvm_va_end.internalName, desc, false)
					}
					"llvm.memcpy.p0i8.p0i8.i64" -> {
						mv.visitMethodInsn(Opcodes.INVOKESTATIC, LlvmRuntime::class.java.internalName, LlvmRuntime::llvm_memcpy_p0i8_p0i8_i64.internalName, desc, false)
					}
					else -> noImpl("Not supported llvm intrinsic ${name.id}")
				}

			} else {
				when (name.id) {
				// builtins!
					"puts", "printf" -> {
						//println("puts: $desc")
						mv.visitMethodInsn(Opcodes.INVOKESTATIC, LlvmRuntime::class.java.internalName, name.id, desc, false)
					}
					else -> {
						mv.visitMethodInsn(Opcodes.INVOKESTATIC, program.className, normalizeMethodName(name.id), desc, false)
					}
				}
			}
			if (stm.target != null) _store(func.type, stm.target)
		}

		override fun visit(stm: Stm.RET) {
			if (preserveSP) {
				_load(Type.INT32, LocalSP)
				mv.PUTSTATIC(SP)
			}

			//println("ret!")
			visit(stm.typedValue)
			mv.vreturn(this.function.type)
		}

		override fun visit(stm: Stm.JUMP) {
			val value = this.phiValues[Pair(currentLabel, stm.branch.id)]
			if (value != null) visit(value)
			mv.visitJumpInsn(Opcodes.GOTO, getLabelByName(stm.branch.id))
		}

		override fun visit(stm: Stm.JUMP_IF) {
			// TRUE
			val valueTrue = this.phiValues[Pair(currentLabel, stm.branchTrue.id)]
			if (valueTrue != null) visit(valueTrue)
			visit(stm.cond)
			mv.visitJumpInsn(Opcodes.IFNE, getLabelByName(stm.branchTrue.id))
			if (valueTrue != null) pop(valueTrue.type)

			// FALSE
			val valueFalse = this.phiValues[Pair(currentLabel, stm.branchFalse.id)]
			if (valueFalse != null) visit(valueFalse)
			mv.visitJumpInsn(Opcodes.GOTO, getLabelByName(stm.branchFalse.id))
		}

		private fun pop(type: Type) {
			mv.visitInsn(if (type.isLongOrDouble()) Opcodes.POP else Opcodes.POP2)
		}

		override fun visit(stm: Stm.PHI) {
			//_load(stm.type, stm.labelsToValues[0])

			// Value is in the stack!
			_store(stm.type, stm.target)
		}

		override fun visit(stm: Stm.TERNARY) {
			visit(stm.cond)
			val flabel = Label()
			val contlabel = Label()
			mv.INT(1)
			mv.visitJumpInsn(Opcodes.IFEQ, flabel)
			visit(stm.vfalse)
			mv.visitJumpInsn(Opcodes.GOTO, contlabel)
			mv.visitLabel(flabel)
			visit(stm.vtrue)
			mv.visitLabel(contlabel)
			_store(stm.vtrue.type, stm.target)
		}

		private fun conv(from: Type, to: Type) {
			fun invalid(): Nothing = noImpl("Not implemented conversion: $from to $to")
			if (from != to) {
				when (from) {
					Type.INT32 -> when (to) {
						Type.INT64 -> mv.visitInsn(Opcodes.I2L)
						else -> invalid()
					}
					Type.INT64 -> when (to) {
						Type.INT32 -> mv.visitInsn(Opcodes.L2I)
						else -> invalid()
					}
					is Type.PTR -> when (to) {
					//Type.INT32 -> Unit
					//Type.INT64 -> mv.visitInsn(Opcodes.I2L)
						is Type.PTR -> Unit
						else -> invalid()
					}
					else -> invalid()
				}
			}
		}

		override fun visit(stm: Stm.SEXT) {
			visit(stm.from)
			conv(stm.from.type, stm.toType)
			_store(stm.toType, stm.target)
		}

		override fun visit(stm: Stm.BITCAST) {
			visit(stm.from)
			conv(stm.from.type, stm.toType)
			_store(stm.toType, stm.target)
		}
	}
}

class MethodRef(val owner: String, val name: String, val desc: String)
class FieldRef(val owner: String, val name: String, val desc: String)

fun MethodVisitor.ADD(type: Type) = when (type) {
	Type.INT32 -> this.visitInsn(Opcodes.IADD)
	Type.INT64 -> this.visitInsn(Opcodes.LADD)
	else -> noImpl("$type")
}

fun MethodVisitor.OR(type: Type) = when (type) {
	Type.INT32 -> this.visitInsn(Opcodes.IOR)
	Type.INT64 -> this.visitInsn(Opcodes.LOR)
	else -> noImpl("$type")
}

fun MethodVisitor.SUB(type: Type) = when (type) {
	Type.INT32 -> this.visitInsn(Opcodes.ISUB)
	Type.INT64 -> this.visitInsn(Opcodes.LSUB)
	else -> noImpl("$type")
}

fun MethodVisitor.MUL(type: Type) = when (type) {
	Type.INT32 -> this.visitInsn(Opcodes.IMUL)
	Type.INT64 -> this.visitInsn(Opcodes.LMUL)
	else -> noImpl("$type")
}

fun MethodVisitor.DIV(type: Type) = when (type) {
	Type.INT32 -> this.visitInsn(Opcodes.IDIV)
	Type.INT64 -> this.visitInsn(Opcodes.LDIV)
	else -> noImpl("$type")
}

fun MethodVisitor.SLT(type: Type) = when (type) {
	Type.INT32 -> this.visitMethodInsn(Opcodes.INVOKESTATIC, LlvmRuntime::class.java.internalName, LlvmRuntime::slt.internalName, "(II)Z", false)
	Type.INT64 -> this.visitMethodInsn(Opcodes.INVOKESTATIC, LlvmRuntime::class.java.internalName, LlvmRuntime::slt.internalName, "(JJ)Z", false)
	else -> noImpl("$type")
}

fun MethodVisitor.ULT(type: Type) = when (type) {
	Type.INT32 -> this.visitMethodInsn(Opcodes.INVOKESTATIC, LlvmRuntime::class.java.internalName, LlvmRuntime::ult.internalName, "(II)Z", false)
	Type.INT64 -> this.visitMethodInsn(Opcodes.INVOKESTATIC, LlvmRuntime::class.java.internalName, LlvmRuntime::ult.internalName, "(JJ)Z", false)
	else -> noImpl("$type")
}

fun MethodVisitor.SGT(type: Type) = when (type) {
	Type.INT32 -> this.visitMethodInsn(Opcodes.INVOKESTATIC, LlvmRuntime::class.java.internalName, LlvmRuntime::sgt.internalName, "(II)Z", false)
	Type.INT64 -> this.visitMethodInsn(Opcodes.INVOKESTATIC, LlvmRuntime::class.java.internalName, LlvmRuntime::sgt.internalName, "(JJ)Z", false)
	else -> noImpl("$type")
}

inline fun <reified T : Any> MethodVisitor.NEWARRAY() {
	NEWARRAY(T::class.java)
}

private fun MethodVisitor._FIELD(opcode: Int, field: FieldRef) {
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
		in Byte.MIN_VALUE..Byte.MAX_VALUE -> visitIntInsn(Opcodes.BIPUSH, value)
		in Short.MIN_VALUE..Short.MAX_VALUE -> visitIntInsn(Opcodes.SIPUSH, value)
		else -> {
			visitLdcInsn(value)
		}
	}
}

fun MethodVisitor.LONG(value: Long) {
	when (value) {
		in Int.MIN_VALUE..Int.MAX_VALUE -> {
			this.INT(value.toInt())
			visitInsn(Opcodes.I2L)
		}
		else -> {
			visitLdcInsn(value)
		}
	}
}

inline fun <reified T : Any> MethodVisitor.RETURN() = this.vreturn(T::class.java)

fun MethodVisitor.vreturn(clazz: Class<*>) = clazz.toType()

fun Class<*>.toType() = when (this) {
	java.lang.Void::class.java -> Type.VOID
	java.lang.Integer::class.java -> Type.INT32
	else -> noImpl("$this")
}

fun MethodVisitor.vreturn(type: Type) {
	when (type) {
		Type.VOID -> visitInsn(RETURN)
		Type.INT32, is Type.PTR -> visitInsn(IRETURN)
		Type.INT64 -> visitInsn(LRETURN)
		else -> invalidOp("Unsupported return $type")
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
