open class ProgramVisitor {
	open fun visit(program: Program) {
		for (decl in program.decls) visit(decl)
	}

	open fun visit(decl: Decl) {
		when (decl) {
			is Decl.EMPTY -> visit(decl)
			is Decl.DEFINE -> visit(decl)
		}
	}

	open fun visit(decl: Decl.EMPTY) {
	}

	open fun visit(decl: Decl.DEFINE) {
	}
}