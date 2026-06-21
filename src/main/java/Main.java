import java.util.Scanner;

public class Main {
    public static void main(String[] args) throws Exception {
        Scanner scanner = new Scanner(System.in);
        Shell shell = new Shell();

        while (true) {
            shell.reapBackgroundJobs(System.out);
            System.out.print("$ ");
            System.out.flush();

            if (!scanner.hasNextLine()) {
                break;
            }

            String input = scanner.nextLine();
            CommandLineParser.Pipeline pipeline = CommandLineParser.parse(input);
            shell.execute(pipeline);
        }
    }
}
