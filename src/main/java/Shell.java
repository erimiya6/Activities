import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

public class Shell {
    private final InheritableThreadLocal<File> currentDirectory = new InheritableThreadLocal<File>() {
        @Override
        protected File initialValue() {
            String userDir = System.getProperty("user.dir");
            return new File(userDir).getAbsoluteFile();
        }
        @Override
        protected File childValue(File parentValue) {
            return parentValue;
        }
    };

    private final Set<String> builtins = new HashSet<>(Arrays.asList("exit", "echo", "type", "pwd", "cd", "jobs"));

    public static class Job {
        public final int id;
        public final String commandLine;
        public final List<Process> processes;
        public String status;

        public Job(int id, String commandLine, List<Process> processes, String status) {
            this.id = id;
            this.commandLine = commandLine;
            this.processes = processes;
            this.status = status;
        }
    }

    private final Map<Integer, Job> activeJobs = new ConcurrentHashMap<>();
    private final AtomicInteger jobCounter = new AtomicInteger(1);

    public Shell() {
    }

    public void execute(CommandLineParser.Pipeline pipeline) {
        if (pipeline.commands.isEmpty()) {
            return;
        }

        if (pipeline.isBackground) {
            int jobId = registerJob(pipeline);
            runPipeline(pipeline, jobId);
        } else {
            runPipeline(pipeline, -1);
        }
    }

    private int registerJob(CommandLineParser.Pipeline pipeline) {
        int id = jobCounter.getAndIncrement();
        String cmdLine = getCommandLineString(pipeline);
        Job job = new Job(id, cmdLine, new ArrayList<>(), "Running");
        activeJobs.put(id, job);
        return id;
    }

    private void deregisterJob(int id) {
        activeJobs.remove(id);
    }

    private String getCommandLineString(CommandLineParser.Pipeline pipeline) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < pipeline.commands.size(); i++) {
            CommandLineParser.Command cmd = pipeline.commands.get(i);
            sb.append(String.join(" ", cmd.arguments));
            if (cmd.stdoutFile != null) {
                sb.append(" ").append(cmd.stdoutAppend ? ">>" : ">").append(" ").append(cmd.stdoutFile);
            }
            if (cmd.stderrFile != null) {
                sb.append(" ").append(cmd.stderrAppend ? "2>>" : "2>").append(" ").append(cmd.stderrFile);
            }
            if (i < pipeline.commands.size() - 1) {
                sb.append(" | ");
            }
        }
        if (pipeline.isBackground) {
            sb.append(" &");
        }
        return sb.toString();
    }

    private void runPipeline(CommandLineParser.Pipeline pipeline, int jobId) {
        int n = pipeline.commands.size();
        if (n == 1) {
            CommandLineParser.Command cmd = pipeline.commands.get(0);
            if (cmd.arguments.isEmpty()) {
                return;
            }
            String commandName = cmd.arguments.get(0);
            if (builtins.contains(commandName) && jobId == -1) {
                executeSingleCommand(cmd);
                return;
            }
        }

        PipedOutputStream[] pouts = new PipedOutputStream[n - 1];
        PipedInputStream[] pins = new PipedInputStream[n - 1];
        for (int i = 0; i < n - 1; i++) {
            try {
                pouts[i] = new PipedOutputStream();
                pins[i] = new PipedInputStream(pouts[i]);
            } catch (IOException e) {
                System.err.println("Error creating pipeline pipes: " + e.getMessage());
                return;
            }
        }

        List<Process> processes = new ArrayList<>();
        List<Thread> copyThreads = new ArrayList<>();
        List<Thread> builtinThreads = new ArrayList<>();

        for (int i = 0; i < n; i++) {
            CommandLineParser.Command cmd = pipeline.commands.get(i);
            if (cmd.arguments.isEmpty()) {
                continue;
            }
            String commandName = cmd.arguments.get(0);

            InputStream stdinSource = (i == 0) ? null : pins[i - 1];
            OutputStream stdoutDest;
            if (i == n - 1) {
                if (cmd.stdoutFile != null) {
                    try {
                        File file = new File(cmd.stdoutFile);
                        if (!file.isAbsolute()) {
                            file = new File(currentDirectory.get(), cmd.stdoutFile);
                        }
                        File parent = file.getParentFile();
                        if (parent != null) parent.mkdirs();
                        stdoutDest = new FileOutputStream(file, cmd.stdoutAppend);
                    } catch (IOException e) {
                        System.err.println(commandName + ": error: " + e.getMessage());
                        continue;
                    }
                } else {
                    stdoutDest = System.out;
                }
            } else {
                stdoutDest = pouts[i];
            }

            OutputStream stderrDest;
            if (cmd.stderrFile != null) {
                try {
                    File file = new File(cmd.stderrFile);
                    if (!file.isAbsolute()) {
                        file = new File(currentDirectory.get(), cmd.stderrFile);
                    }
                    File parent = file.getParentFile();
                    if (parent != null) parent.mkdirs();
                    stderrDest = new FileOutputStream(file, cmd.stderrAppend);
                } catch (IOException e) {
                    System.err.println(commandName + ": error: " + e.getMessage());
                    continue;
                }
            } else {
                stderrDest = System.err;
            }

            if (builtins.contains(commandName)) {
                final int index = i;
                final PrintStream outStream = (stdoutDest instanceof PrintStream) ? (PrintStream) stdoutDest : new PrintStream(stdoutDest);
                final PrintStream errStream = (stderrDest instanceof PrintStream) ? (PrintStream) stderrDest : new PrintStream(stderrDest);
                Thread t = new Thread(() -> {
                    try {
                        executeBuiltin(cmd, outStream, errStream);
                    } finally {
                        outStream.flush();
                        errStream.flush();
                        if (index < n - 1 || cmd.stdoutFile != null) {
                            try { stdoutDest.close(); } catch (IOException ignored) {}
                        }
                        if (cmd.stderrFile != null) {
                            try { stderrDest.close(); } catch (IOException ignored) {}
                        }
                    }
                });
                t.setDaemon(true);
                t.start();
                builtinThreads.add(t);
            } else {
                String executablePath = resolveExecutable(commandName);
                if (executablePath == null) {
                    PrintStream errStream = new PrintStream(stderrDest);
                    errStream.println(commandName + ": command not found");
                    errStream.flush();
                    if (i < n - 1) {
                        try { pouts[i].close(); } catch (IOException ignored) {}
                    }
                    continue;
                }

                try {
                    ProcessBuilder pb = new ProcessBuilder(cmd.arguments);
                    pb.directory(currentDirectory.get());

                    if (cmd.stderrFile != null) {
                        File file = new File(cmd.stderrFile);
                        if (!file.isAbsolute()) {
                            file = new File(currentDirectory.get(), cmd.stderrFile);
                        }
                        pb.redirectError(cmd.stderrAppend ? ProcessBuilder.Redirect.appendTo(file) : ProcessBuilder.Redirect.to(file));
                    } else {
                        pb.redirectError(ProcessBuilder.Redirect.INHERIT);
                    }

                    if (i == 0) {
                        pb.redirectInput(ProcessBuilder.Redirect.INHERIT);
                    } else {
                        pb.redirectInput(ProcessBuilder.Redirect.PIPE);
                    }

                    if (i == n - 1) {
                        if (cmd.stdoutFile != null) {
                          File file = new File(cmd.stdoutFile);
                          if (!file.isAbsolute()) {
                              file = new File(currentDirectory.get(), cmd.stdoutFile);
                          }
                          pb.redirectOutput(cmd.stdoutAppend ? ProcessBuilder.Redirect.appendTo(file) : ProcessBuilder.Redirect.to(file));
                        } else {
                          pb.redirectOutput(ProcessBuilder.Redirect.INHERIT);
                        }
                    } else {
                        pb.redirectOutput(ProcessBuilder.Redirect.PIPE);
                    }

                    Process process = pb.start();
                    processes.add(process);

                    if (i > 0) {
                        final InputStream in = stdinSource;
                        final OutputStream out = process.getOutputStream();
                        Thread t = new Thread(() -> copyStream(in, out, true));
                        t.setDaemon(true);
                        t.start();
                        copyThreads.add(t);
                    }
                    if (i < n - 1) {
                        final InputStream in = process.getInputStream();
                        final OutputStream out = stdoutDest;
                        Thread t = new Thread(() -> copyStream(in, out, true));
                        t.setDaemon(true);
                        t.start();
                        copyThreads.add(t);
                    }

                } catch (IOException e) {
                    PrintStream errStream = new PrintStream(stderrDest);
                    errStream.println(commandName + ": error: " + e.getMessage());
                    errStream.flush();
                    if (i < n - 1) {
                        try { pouts[i].close(); } catch (IOException ignored) {}
                    }
                }
            }
        }

        if (jobId != -1) {
            Job job = activeJobs.get(jobId);
            if (job != null) {
                job.processes.addAll(processes);
            }
            if (!processes.isEmpty()) {
                long lastPid = processes.get(processes.size() - 1).pid();
                System.out.println("[" + jobId + "] " + lastPid);
            } else {
                System.out.println("[" + jobId + "] started");
            }
            System.out.flush();

            Thread waitThread = new Thread(() -> {
                try {
                    for (Process p : processes) {
                        try {
                            p.waitFor();
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        }
                    }

                    for (Thread t : builtinThreads) {
                        try {
                            t.join();
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        }
                    }

                    for (Thread t : copyThreads) {
                        try {
                            t.join(200);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        }
                    }

                    String cmdLine = getCommandLineString(pipeline);
                    System.out.println("[" + jobId + "]+  Done                    " + cmdLine);
                    System.out.flush();
                } finally {
                    deregisterJob(jobId);
                }
            });
            waitThread.setDaemon(true);
            waitThread.start();
            return;
        }

        for (Process p : processes) {
            try {
                p.waitFor();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        for (Thread t : builtinThreads) {
            try {
                t.join();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        for (Thread t : copyThreads) {
            try {
                t.join(200);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private void executeSingleCommand(CommandLineParser.Command cmd) {
        String commandName = cmd.arguments.get(0);

        PrintStream originalOut = System.out;
        PrintStream originalErr = System.err;
        PrintStream out = originalOut;
        PrintStream err = originalErr;

        try {
            if (cmd.stdoutFile != null) {
                File file = new File(cmd.stdoutFile);
                if (!file.isAbsolute()) {
                    file = new File(currentDirectory.get(), cmd.stdoutFile);
                }
                File parent = file.getParentFile();
                if (parent != null) {
                    parent.mkdirs();
                }
                out = new PrintStream(new FileOutputStream(file, cmd.stdoutAppend));
            }
            if (cmd.stderrFile != null) {
                File file = new File(cmd.stderrFile);
                if (!file.isAbsolute()) {
                    file = new File(currentDirectory.get(), cmd.stderrFile);
                }
                File parent = file.getParentFile();
                if (parent != null) {
                    parent.mkdirs();
                }
                err = new PrintStream(new FileOutputStream(file, cmd.stderrAppend));
            }

            if (builtins.contains(commandName)) {
                executeBuiltin(cmd, out, err);
            } else {
                executeExternalSingle(cmd, out, err);
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
                out.println(currentDirectory.get().getAbsolutePath());
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
                        targetDir = new File(currentDirectory.get(), targetPath);
                    }
                }

                try {
                    File canonicalDir = targetDir.getCanonicalFile();
                    if (canonicalDir.exists() && canonicalDir.isDirectory()) {
                        currentDirectory.set(canonicalDir);
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

            case "jobs":
                List<Integer> sortedIds = new ArrayList<>(activeJobs.keySet());
                Collections.sort(sortedIds);
                for (int id : sortedIds) {
                    Job job = activeJobs.get(id);
                    if (job != null) {
                        out.println("[" + job.id + "]+  " + job.status + "               " + job.commandLine);
                    }
                }
                break;
        }
    }

    private void executeExternalSingle(CommandLineParser.Command cmd, PrintStream out, PrintStream err) {
        String commandName = cmd.arguments.get(0);
        String executablePath = resolveExecutable(commandName);

        if (executablePath == null) {
            err.println(commandName + ": command not found");
            return;
        }

        try {
            ProcessBuilder pb = new ProcessBuilder(cmd.arguments);
            pb.directory(currentDirectory.get());

            if (cmd.stdoutFile != null) {
                File file = new File(cmd.stdoutFile);
                if (!file.isAbsolute()) {
                    file = new File(currentDirectory.get(), cmd.stdoutFile);
                }
                pb.redirectOutput(cmd.stdoutAppend ? ProcessBuilder.Redirect.appendTo(file) : ProcessBuilder.Redirect.to(file));
            } else {
                pb.redirectOutput(ProcessBuilder.Redirect.INHERIT);
            }

            if (cmd.stderrFile != null) {
                File file = new File(cmd.stderrFile);
                if (!file.isAbsolute()) {
                    file = new File(currentDirectory.get(), cmd.stderrFile);
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
                file = new File(currentDirectory.get(), name);
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

    private void copyStream(InputStream in, OutputStream out, boolean closeOut) {
        byte[] buffer = new byte[4096];
        int read;
        try {
            while ((read = in.read(buffer)) != -1) {
                out.write(buffer, 0, read);
                out.flush();
            }
        } catch (IOException e) {
            // Stream closed or broken pipe - expected when process exits
        } finally {
            if (closeOut) {
                try {
                    out.close();
                } catch (IOException ignored) {}
            }
            try {
                in.close();
            } catch (IOException ignored) {}
        }
    }
}
