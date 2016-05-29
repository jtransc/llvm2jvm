import java.nio.ByteBuffer;

public class LlvmRuntime {
	static public ByteBuffer MEM = ByteBuffer.allocateDirect(0x100000);
	static public int SP = MEM.limit() - 0x10;

	static public int li32(int offset) {
		//System.out.println("mem[" + offset + "]");
		return MEM.getInt(offset);
	}

	static public void si32(int offset, int value) {
		//System.out.println("mem[" + offset + "] = " + value);
		MEM.putInt(offset, value);
	}

	static public int alloca(int size) {
		SP -= size;
		//System.out.println("alloca: " + size);
		return SP;
	}
}
