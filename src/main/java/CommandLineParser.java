import java.util.ArrayList;
import java.util.List;

public class CommandLineParser {

    public static class Command {
        public final List<String> arguments;
        public final String stdoutFile;
        public final boolean stdoutAppend;
        public final String stderrFile;
        public final boolean stderrAppend;

        public Command(List<String> arguments, String stdoutFile, boolean stdoutAppend, 
                       String stderrFile, boolean stderrAppend) {
            this.arguments = arguments;
            this.stdoutFile = stdoutFile;
            this.stdoutAppend = stdoutAppend;
            this.stderrFile = stderrFile;
            this.stderrAppend = stderrAppend;
        }
    }

    public static class Pipeline {
        public final List<Command> commands;
        public final boolean isBackground;

        public Pipeline(List<Command> commands, boolean isBackground) {
            this.commands = commands;
            this.isBackground = isBackground;
        }
    }

    public static Pipeline parse(String line) {
        return parseInline(line);
    }

    private static Pipeline parseInline(String line) {
        List<Command> commands = new ArrayList<>();
        boolean isBackground = false;

        List<String> tokens = new ArrayList<>();
        StringBuilder currentToken = new StringBuilder();
        boolean inSingleQuotes = false;
        boolean inDoubleQuotes = false;
        boolean tokenStarted = false;

        String stdoutFile = null;
        boolean stdoutAppend = false;
        String stderrFile = null;
        boolean stderrAppend = false;

        boolean nextTokenIsStdoutFile = false;
        boolean nextTokenIsStdoutAppendFile = false;
        boolean nextTokenIsStderrFile = false;
        boolean nextTokenIsStderrAppendFile = false;

        int len = line.length();
        for (int i = 0; i < len; i++) {
            char c = line.charAt(i);

            if (inSingleQuotes) {
                if (c == '\'') {
                    inSingleQuotes = false;
                } else {
                    currentToken.append(c);
                }
            } else if (inDoubleQuotes) {
                if (c == '"') {
                    inDoubleQuotes = false;
                } else if (c == '\\') {
                    if (i + 1 < len) {
                        char next = line.charAt(i + 1);
                        if (next == '"' || next == '\\' || next == '$' || next == '`') {
                            currentToken.append(next);
                            i++;
                        } else {
                            currentToken.append(c);
                        }
                    } else {
                        currentToken.append(c);
                    }
                } else {
                    currentToken.append(c);
                }
            } else {
                if (c == '\'') {
                    inSingleQuotes = true;
                    tokenStarted = true;
                } else if (c == '"') {
                    inDoubleQuotes = true;
                    tokenStarted = true;
                } else if (c == '\\') {
                    if (i + 1 < len) {
                        currentToken.append(line.charAt(i + 1));
                        i++;
                    } else {
                        currentToken.append(c);
                    }
                    tokenStarted = true;
                } else if (c == '>') {
                    boolean isStderr = false;
                    String curr = currentToken.toString();
                    if (curr.equals("2")) {
                        isStderr = true;
                        currentToken.setLength(0);
                    } else if (curr.equals("1")) {
                        isStderr = false;
                        currentToken.setLength(0);
                    } else {
                        isStderr = false;
                        if (tokenStarted) {
                            String tok = currentToken.toString();
                            if (nextTokenIsStdoutFile) {
                                stdoutFile = tok;
                                nextTokenIsStdoutFile = false;
                            } else if (nextTokenIsStdoutAppendFile) {
                                stdoutFile = tok;
                                stdoutAppend = true;
                                nextTokenIsStdoutAppendFile = false;
                            } else if (nextTokenIsStderrFile) {
                                stderrFile = tok;
                                nextTokenIsStderrFile = false;
                            } else if (nextTokenIsStderrAppendFile) {
                                stderrFile = tok;
                                stderrAppend = true;
                                nextTokenIsStderrAppendFile = false;
                            } else {
                                tokens.add(tok);
                            }
                            currentToken.setLength(0);
                        }
                    }

                    boolean isAppend = false;
                    if (i + 1 < len && line.charAt(i + 1) == '>') {
                        isAppend = true;
                        i++;
                    }

                    if (isStderr) {
                        if (isAppend) nextTokenIsStderrAppendFile = true;
                        else nextTokenIsStderrFile = true;
                    } else {
                        if (isAppend) nextTokenIsStdoutAppendFile = true;
                        else nextTokenIsStdoutFile = true;
                    }
                    tokenStarted = false;
                } else if (c == '|') {
                    if (tokenStarted) {
                        String tok = currentToken.toString();
                        if (nextTokenIsStdoutFile) {
                            stdoutFile = tok;
                            nextTokenIsStdoutFile = false;
                        } else if (nextTokenIsStdoutAppendFile) {
                            stdoutFile = tok;
                            stdoutAppend = true;
                            nextTokenIsStdoutAppendFile = false;
                        } else if (nextTokenIsStderrFile) {
                            stderrFile = tok;
                            nextTokenIsStderrFile = false;
                        } else if (nextTokenIsStderrAppendFile) {
                            stderrFile = tok;
                            stderrAppend = true;
                            nextTokenIsStderrAppendFile = false;
                        } else {
                            tokens.add(tok);
                        }
                        currentToken.setLength(0);
                        tokenStarted = false;
                    }
                    commands.add(new Command(new ArrayList<>(tokens), stdoutFile, stdoutAppend, stderrFile, stderrAppend));
                    tokens.clear();
                    stdoutFile = null;
                    stdoutAppend = false;
                    stderrFile = null;
                    stderrAppend = false;
                } else if (c == '&') {
                    if (tokenStarted) {
                        String tok = currentToken.toString();
                        if (nextTokenIsStdoutFile) {
                            stdoutFile = tok;
                            nextTokenIsStdoutFile = false;
                        } else if (nextTokenIsStdoutAppendFile) {
                            stdoutFile = tok;
                            stdoutAppend = true;
                            nextTokenIsStdoutAppendFile = false;
                        } else if (nextTokenIsStderrFile) {
                            stderrFile = tok;
                            nextTokenIsStderrFile = false;
                        } else if (nextTokenIsStderrAppendFile) {
                            stderrFile = tok;
                            stderrAppend = true;
                            nextTokenIsStderrAppendFile = false;
                        } else {
                            tokens.add(tok);
                        }
                        currentToken.setLength(0);
                        tokenStarted = false;
                    }
                    isBackground = true;
                } else if (Character.isWhitespace(c)) {
                    if (tokenStarted) {
                        String tok = currentToken.toString();
                        if (nextTokenIsStdoutFile) {
                            stdoutFile = tok;
                            nextTokenIsStdoutFile = false;
                        } else if (nextTokenIsStdoutAppendFile) {
                            stdoutFile = tok;
                            stdoutAppend = true;
                            nextTokenIsStdoutAppendFile = false;
                        } else if (nextTokenIsStderrFile) {
                            stderrFile = tok;
                            nextTokenIsStderrFile = false;
                        } else if (nextTokenIsStderrAppendFile) {
                            stderrFile = tok;
                            stderrAppend = true;
                            nextTokenIsStderrAppendFile = false;
                        } else {
                            tokens.add(tok);
                        }
                        currentToken.setLength(0);
                        tokenStarted = false;
                    }
                } else {
                    currentToken.append(c);
                    tokenStarted = true;
                }
            }
        }

        if (tokenStarted || !tokens.isEmpty() || stdoutFile != null || stderrFile != null) {
            if (tokenStarted) {
                String tok = currentToken.toString();
                if (nextTokenIsStdoutFile) {
                    stdoutFile = tok;
                } else if (nextTokenIsStdoutAppendFile) {
                    stdoutFile = tok;
                    stdoutAppend = true;
                } else if (nextTokenIsStderrFile) {
                    stderrFile = tok;
                } else if (nextTokenIsStderrAppendFile) {
                    stderrFile = tok;
                    stderrAppend = true;
                } else {
                    tokens.add(tok);
                }
            }
            commands.add(new Command(new ArrayList<>(tokens), stdoutFile, stdoutAppend, stderrFile, stderrAppend));
        }

        return new Pipeline(commands, isBackground);
    }
}
