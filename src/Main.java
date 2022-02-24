import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.text.ParseException;

public class Main {
    public static void main(String[] args) {
        if (args.length != 2) {
            System.err.println("Please, enter two arguments - input and output file names");
            return;
        }
        File inputFile = new File(args[0]);
        byte[] fileContent;
        try {
            fileContent = Files.readAllBytes(inputFile.toPath());
        } catch (IOException e) {
            System.err.println("Sorry, an error occurred while reading input file " + e.getMessage());
            return;
        }
        ByteSource source = new ByteSource(fileContent);
        ByteParser parser = new ElfParser(source);
        try (BufferedWriter writer = new BufferedWriter(new FileWriter(args[1], StandardCharsets.UTF_8))) {
            writer.write(parser.parse());
        } catch (ParseException e) {
            System.err.println("The input file was probably incorrect :( \n" + e.getMessage());
        } catch (IOException e) {
            System.err.println("Sorry, an error occurred while output");
        }
    }
}
