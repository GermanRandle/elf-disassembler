import java.text.ParseException;

public class ByteSource implements Source {
    private final long[] content;
    private long pos;

    public ByteSource(byte[] bytes) {
        long[] longs = new long[bytes.length];
        for (int i = 0; i < bytes.length; i++) {
            longs[i] = bytes[i];
            if (longs[i] < 0) {
                longs[i] += 256;
            }
        }
        this.content = longs;
    }

    public boolean hasNext() {
        return pos < content.length;
    }

    public long getNext() {
        return content[(int) pos++];
    }

    public long getPos() {
        return pos;
    }

    public void setPos(long newPos) {
        pos = newPos;
    }

    public void error(String message) throws ParseException {
        throw new ParseException("Error while parsing: " + message + "\nPosition #", (int) pos);
    }
}