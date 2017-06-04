package SeizureSpeedUtils;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.*;

import static java.nio.charset.StandardCharsets.UTF_8;

public class Leaderboard {

    private static String dataFileName = "s.txt";
    private static File dataFile;

    private static List<SeizureSpeedScore> scores = new ArrayList<>();
    private static final Object scoresLock = new Object();
    private static String nextId = "s1";
    private static final Object nextIdLock = new Object();


    static {
        String dataFilePath = new File(Leaderboard.class.getProtectionDomain().getCodeSource().getLocation().getFile()).
                getParent() + System.getProperty("file.separator") + dataFileName;
        dataFile = new File(dataFilePath);

        if(!dataFile.isFile()) {
            try(PrintWriter out = new PrintWriter(new OutputStreamWriter(new FileOutputStream(dataFile, true), UTF_8), true)) {
                out.println(nextId);
            } catch(IOException e) {
                e.printStackTrace();
            }
        } else {
            try {
                List<String> data = Files.readAllLines(Paths.get(dataFile.toURI()));
                nextId = data.remove(0);
                for(String s : data) {
                    if(s.isEmpty()) continue;
                    String[] fields = s.split(":");
                    scores.add(new SeizureSpeedScore(fields[0], fields[1], Integer.parseInt(fields[2])));
                }
            } catch(IOException e) {
                e.printStackTrace();
            }
        }
    }

    public static String getNextId() {
        synchronized(nextIdLock) {
            String retval = nextId;
            nextId = "s" + (Integer.parseInt(nextId.substring(1)) + 1);
            try(PrintWriter out = new PrintWriter(new OutputStreamWriter(new FileOutputStream(dataFile, false), UTF_8), true)) {
                out.println(nextId);
                for(SeizureSpeedScore sc : scores) {
                    out.format(String.format("%s:%s:%d\n", sc.id, sc.name, sc.score));
                }
            } catch(IOException e) {
                e.printStackTrace();
            }
            return retval;
        }
    }

    public static String current(String reqId) {
        boolean reqTop5 = false;
        StringBuilder retval = new StringBuilder();
        synchronized(scoresLock) {
            for(int i = 0; i < 5 && i < scores.size(); i++) {
                SeizureSpeedScore sc = scores.get(i);
                retval.append(String.format("%d:%s:%d\n", reqId.equals(sc.id) ? 1 : 0, sc.name, sc.score));
                reqTop5 |= reqId.equals(sc.id);
            }
            if(scores.size() > 5) {
                if(!reqTop5) {
                    for(int i = 5; i < scores.size(); i++) {
                        SeizureSpeedScore sc = scores.get(i);
                        if(reqId.equals(sc.id)) {
                            retval.append(String.format("1:%s:%d", sc.name, sc.score));
                            return retval.toString();
                        }
                    }
                }
                SeizureSpeedScore sc = scores.get(5);
                retval.append(String.format("%d:%s:%d", reqId.equals(sc.id) ? 1 : 0, sc.name, sc.score));
            } else {
                retval.deleteCharAt(retval.length() - 1);  // remove trailing newline
            }
            return retval.toString();
        }
    }

    private static void prune() {
        synchronized(scoresLock) {
            Collections.sort(scores);
            Map<String, Integer> nKept = new HashMap<>();
            for(int i = 0; i < scores.size(); i++) {
                SeizureSpeedScore sc = scores.get(i);
                Integer k = nKept.get(sc.id);
                if(k == null) k = 0;
                if(i > 5 && k >= 1) {
                    scores.remove(i);
                    i--;
                } else {
                    nKept.put(sc.id, k + 1);
                }
            }
            try(PrintWriter out = new PrintWriter(new OutputStreamWriter(new FileOutputStream(dataFile, false), UTF_8), true)) {
                out.println(nextId);
                for(SeizureSpeedScore sc : scores) {
                    out.format(String.format("%s:%s:%d\n", sc.id, sc.name, sc.score));
                }
            } catch(IOException e) {
                e.printStackTrace();
            }
        }
    }

    public static String update(String reqScore) {
        String[] fields = reqScore.split(":");
        synchronized(scoresLock) {
            scores.add(new SeizureSpeedScore(fields[0], fields[1], Integer.parseInt(fields[2])));
            prune();
            return current(fields[0]);
        }
    }

    static class SeizureSpeedScore implements Comparable<SeizureSpeedScore> {
        String id;
        String name;
        int score;

        @Override
        public int hashCode() {
            return Integer.valueOf(score).hashCode();
        }

        @Override
        public boolean equals(Object obj) {
            return (obj instanceof SeizureSpeedScore) && (score == ((SeizureSpeedScore)obj).score);
        }

        @Override
        public int compareTo(SeizureSpeedScore o) {
            return -Integer.compare(score, o.score);
        }

        SeizureSpeedScore(String id, String name, int score) {
            this.id = id;
            this.name = name;
            this.score = score;
        }
    }

}
