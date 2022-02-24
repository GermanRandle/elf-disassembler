import java.text.ParseException;

public abstract class ByteParser {
    protected final ByteSource source;

    protected ByteParser(final ByteSource source) {
        this.source = source;
    }

    protected long read() throws ParseException {
        if (!source.hasNext()) {
            error("Unexpected end of file");
        }
        return source.getNext();
    }

    protected long read2() throws ParseException {
        long first = read();
        return (read() << 8) | first;
    }

    protected long read4() throws ParseException {
        long[] bytes = new long[4];
        for (int i = 0; i < 4; i++) {
            bytes[i] = read();
        }
        return (bytes[3] << 24) | (bytes[2] << 16) | (bytes[1] << 8) | bytes[0];
    }

    protected void expect(long expected) throws ParseException {
        long taken = read();
        if (taken != expected) {
            error("Expected \"" + expected + "\", but found \"" + taken);
        }
    }

    protected void moveTo(long pos) {
        source.setPos(pos);
    }

    protected void jumpOver(long length) {
        source.setPos(source.getPos() + length);
    }

    protected long getPos() {
        return source.getPos();
    }

    protected void error(String message) throws ParseException {
        source.error(message);
    }

    public abstract String parse() throws ParseException;
}
