import com.jtransc.error.noImpl

open class ProgramVisitor {
	open fun visit(program: Program) {
		for (decl in program.decls) visit(decl)
	}

	final fun visit(decl: Decl) {
		when (decl) {
			is Decl.EMPTY -> visit(decl)
			is Decl.DECFUN -> visit(decl)
		}
	}

	open fun visit(decl: Decl.EMPTY) {
	}

	open fun visit(decl: Decl.DECFUN) {
		visit(decl.type)
		visit(decl.name)
		for (arg in decl.args) visit(arg)
		visit(decl.body)
	}

	open fun visit(type: Type) {
	}

	open fun visit(ref: Reference) {
	}

	open fun visit(ref: Value) {
	}

	open fun visit(arg: Argument) {
		visit(arg.type)
		visit(arg.name)
	}

	open fun visit(typedValue: TypedValue) {
		visit(typedValue.type)
		visit(typedValue.value)
	}

	open fun visit(body: Body) {
		for (stm in body.stms) visit(stm)
	}

	open fun visit(stm: Stm) {
		when (stm) {
			is Stm.ADD -> visit(stm)
			is Stm.ALLOCA -> visit(stm)
			is Stm.CALL -> visit(stm)
			is Stm.LOAD -> visit(stm)
			is Stm.RET -> visit(stm)
			is Stm.STORE -> visit(stm)
			else -> noImpl("Not implemented stm: $stm")
		}
	}

	open fun visit(stm: Stm.ADD) {
		visit(stm.target)
		visit(stm.left)
		visit(stm.right)
		visit(stm.type)
	}

	open fun visit(stm: Stm.ALLOCA) {
		visit(stm.target)
		visit(stm.type)
	}

	open fun visit(stm: Stm.CALL) {
		visit(stm.target)
		visit(stm.rettype)
		visit(stm.name)
		for (arg in stm.args) visit(arg)
	}

	open fun visit(stm: Stm.LOAD) {
		visit(stm.target)
		visit(stm.targetType)
		visit(stm.from)
	}

	open fun visit(stm: Stm.RET) {
		visit(stm.typedValue)
	}

	open fun visit(stm: Stm.STORE) {
		visit(stm.src)
		visit(stm.dst)
	}
}