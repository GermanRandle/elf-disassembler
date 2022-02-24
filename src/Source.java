import java.text.ParseException;

public interface Source {
    boolean hasNext();
    long getNext();
    void setPos(long newPos);
    void error(String message) throws ParseException;
}
