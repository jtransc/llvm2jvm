import java.nio.ByteBuffer;
import java.nio.ByteOrder;

@SuppressWarnings("WeakerAccess")
public class LlvmRuntime {
    static public ByteBuffer MEM = ByteBuffer.allocateDirect(0x100000).order(ByteOrder.LITTLE_ENDIAN);
    static public int SP = MEM.limit() - 0x10;

    static public int li8(int offset) {
        return MEM.get(offset) & 0xFF;
    }

    static public void si8(int offset, int value) {
        MEM.put(offset, (byte) value);
    }

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

    static public int getelementptr(int offset, int index) {
        return offset + index;
    }

    static public void initStaticData(String data) {
        for (int n = 0; n < data.length(); n++) {
            byte v = (byte) data.charAt(n);
            MEM.put(n, v);
            //System.out.println("PUT: " + n + ": " + v);
        }
    }

    static public int puts(int ptr) {
        while (true) {
            char c = (char) li8(ptr);
            if (c == 0) break;
            System.out.print(c);
            ptr++;
        }
        return 0;
    }
}
