import java.nio.ByteBuffer;

public class LlvmRuntime {
	static private ByteBuffer data = ByteBuffer.allocateDirect(0x100000);
	static private int stack = data.limit() - 4;

	static public int li32(int offset) {
		//System.out.println("mem[" + offset + "]");
		return data.getInt(offset);
	}

	static public void si32(int offset, int value) {
		//System.out.println("mem[" + offset + "] = " + value);
		data.putInt(offset, value);
	}

	static public int alloca(int size) {
		stack -= size;
		//System.out.println("alloca: " + size);
		return stack;
	}
}
