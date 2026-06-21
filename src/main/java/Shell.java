import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

public class Shell {
    private File currentDirectory;
    private final Set<String> builtins = new HashSet<>(Arrays.asList("exit", "echo", "type", "pwd", "cd"));

    public Shell() {
        String userDir = System.getProperty("user.dir");
        this.currentDirectory = new File(userDir).getAbsoluteFile();
    }

    public void execute(CommandLineParser.Command cmd) {
        if (cmd.arguments.isEmpty()) {
            return;
        }

        String commandName = cmd.arguments.get(0);

        // Resolve redirection streams for builtins and shell level errors
        PrintStream originalOut = System.out;
        PrintStream originalErr = System.err;
        PrintStream out = originalOut;
        PrintStream err = originalErr;

        try {
            if (cmd.stdoutFile != null) {
                File file = new File(cmd.stdoutFile);
                if (!file.isAbsolute()) {
                    file = new File(currentDirectory, cmd.stdoutFile);
                }
                // Ensure parent directory exists
                File parent = file.getParentFile();
                if (parent != null) {
                    parent.mkdirs();
                }
                out = new PrintStream(new FileOutputStream(file, cmd.stdoutAppend));
            }
            if (cmd.stderrFile != null) {
                File file = new File(cmd.stderrFile);
                if (!file.isAbsolute()) {
                    file = new File(currentDirectory, cmd.stderrFile);
                }
                // Ensure parent directory exists
                File parent = file.getParentFile();
                if (parent != null) {
                    parent.mkdirs();
                }
                err = new PrintStream(new FileOutputStream(file, cmd.stderrAppend));
            }

            if (builtins.contains(commandName)) {
                executeBuiltin(cmd, out, err);
            } else {
                executeExternal(cmd, out, err);
            }

        } catch (IOException e) {
            err.println(commandName + ": error: " + e.getMessage());
        } finally {
            if (out != originalOut) {
                out.close();
            }
            if (err != originalErr) {
                err.close();
            }
        }
    }

    private void executeBuiltin(CommandLineParser.Command cmd, PrintStream out, PrintStream err) {
        String commandName = cmd.arguments.get(0);

        switch (commandName) {
            case "exit":
                int status = 0;
                if (cmd.arguments.size() > 1) {
                    try {
                        status = Integer.parseInt(cmd.arguments.get(1));
                    } catch (NumberFormatException e) {
                        status = 0;
                    }
                }
                System.exit(status);
                break;

            case "echo":
                for (int i = 1; i < cmd.arguments.size(); i++) {
                    out.print(cmd.arguments.get(i));
                    if (i < cmd.arguments.size() - 1) {
                        out.print(" ");
                    }
                }
                out.println();
                break;

            case "pwd":
                out.println(currentDirectory.getAbsolutePath());
                break;

            case "cd":
                String targetPath = "~";
                if (cmd.arguments.size() > 1) {
                    targetPath = cmd.arguments.get(1);
                }

                File targetDir;
                if (targetPath.equals("~")) {
                    String home = System.getenv("HOME");
                    if (home == null) {
                        home = System.getProperty("user.home");
                    }
                    targetDir = new File(home).getAbsoluteFile();
                } else {
                    targetDir = new File(targetPath);
                    if (!targetDir.isAbsolute()) {
                        targetDir = new File(currentDirectory, targetPath);
                    }
                }

                try {
                    File canonicalDir = targetDir.getCanonicalFile();
                    if (canonicalDir.exists() && canonicalDir.isDirectory()) {
                        currentDirectory = canonicalDir;
                    } else {
                        err.println("cd: " + targetPath + ": No such file or directory");
                    }
                } catch (IOException e) {
                    err.println("cd: " + targetPath + ": No such file or directory");
                }
                break;

            case "type":
                if (cmd.arguments.size() > 1) {
                    String targetCmd = cmd.arguments.get(1);
                    if (builtins.contains(targetCmd)) {
                        out.println(targetCmd + " is a shell builtin");
                    } else {
                        String executablePath = resolveExecutable(targetCmd);
                        if (executablePath != null) {
                            out.println(targetCmd + " is " + executablePath);
                        } else {
                            out.println(targetCmd + ": not found");
                        }
                    }
                }
                break;
        }
    }

    private void executeExternal(CommandLineParser.Command cmd, PrintStream out, PrintStream err) {
        String commandName = cmd.arguments.get(0);
        String executablePath = resolveExecutable(commandName);

        if (executablePath == null) {
            err.println(commandName + ": command not found");
            return;
        }

        try {
            ProcessBuilder pb = new ProcessBuilder(cmd.arguments);
            pb.directory(currentDirectory);

            // Handle redirection for external processes
            if (cmd.stdoutFile != null) {
                File file = new File(cmd.stdoutFile);
                if (!file.isAbsolute()) {
                    file = new File(currentDirectory, cmd.stdoutFile);
                }
                pb.redirectOutput(cmd.stdoutAppend ? ProcessBuilder.Redirect.appendTo(file) : ProcessBuilder.Redirect.to(file));
            } else {
                pb.redirectOutput(ProcessBuilder.Redirect.INHERIT);
            }

            if (cmd.stderrFile != null) {
                File file = new File(cmd.stderrFile);
                if (!file.isAbsolute()) {
                    file = new File(currentDirectory, cmd.stderrFile);
                }
                pb.redirectError(cmd.stderrAppend ? ProcessBuilder.Redirect.appendTo(file) : ProcessBuilder.Redirect.to(file));
            } else {
                pb.redirectError(ProcessBuilder.Redirect.INHERIT);
            }

            pb.redirectInput(ProcessBuilder.Redirect.INHERIT);

            Process process = pb.start();
            process.waitFor();
        } catch (IOException | InterruptedException e) {
            err.println(commandName + ": error executing: " + e.getMessage());
        }
    }

    private String resolveExecutable(String name) {
        if (name.contains("/") || name.contains(File.separator)) {
            File file = new File(name);
            if (!file.isAbsolute()) {
                file = new File(currentDirectory, name);
            }
            if (file.exists() && file.isFile()) {
                return file.getAbsolutePath();
            }
            return null;
        }

        String pathEnv = System.getenv("PATH");
        if (pathEnv == null) {
            return null;
        }

        String[] paths = pathEnv.split(File.pathSeparator);
        for (String path : paths) {
            File dir = new File(path);
            File file = new File(dir, name);
            if (file.exists() && file.isFile() && file.canExecute()) {
                return file.getAbsolutePath();
            }
        }

        return null;
    }
}
