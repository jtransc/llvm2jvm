import com.jtransc.error.noImpl

fun Program.dump() {
	for (decl in this.decls) {
		decl.dump()
		println()
	}
}

fun Decl.dump() {
	when (this) {
		Decl.EMPTY -> println("// Decl.EMPTY")
		is Decl.DECFUN -> {
			println(this.type.dump() + " " + this.name.dump() + "(" + this.args.map { it.dump() }.joinToString(", ") + ") {")
			this.body.dump()
			println("}")
		}
		else -> noImpl("Unsupported $this")
	}
}

fun Body.dump() {
	for (stm in this.stms) {
		stm.dump()
	}
}

fun Stm.dump() {
	when (this) {
		is Stm.ALLOCA -> {
			println("  ${this.target.dump()} = alloca ${this.type.dump()}")
		}
		is Stm.STORE -> {
			println("  store ${this.src.dump()}, ${this.dst.dump()}")
		}
		is Stm.LOAD -> {
			println("  ${this.target.dump()} = load ${this.targetType.dump()}, ${this.from.dump()}")
		}
		is Stm.ADD -> {
			println("  ${this.target.dump()} = add ${this.type.dump()} ${this.left.dump()}, ${this.right.dump()}")
		}
		is Stm.RET -> {
			println("  ret ${this.typedValue.dump()}")
		}
		is Stm.CALL -> {
			println("  ${this.target.dump()} = call ${this.rettype.dump()} ${this.name.dump()}(" + this.args.map { it.dump() }.joinToString(", ") + ")")
		}
		else -> noImpl("Stm.dump: $this")
	}
}

fun Argument.dump(): String = "${this.type.dump()} ${this.name.dump()}"

fun Type.dump(): String = when (this) {
	is Type.INT -> "i${this.width}"
	is Type.PTR -> "${this.type.dump()}*"
	else -> "$this"
}

fun Reference.dump(): String = when (this) {
	is LOCAL -> "%$id"
	is GLOBAL -> "@$id"
	else -> "$this"
}

fun Value.dump(): String = when (this) {
	is Reference -> this.dump()
	is INT -> "${this.value}"
	else -> "$this"
}

fun TypedValue.dump(): String = "${this.type.dump()} ${this.value.dump()}"

