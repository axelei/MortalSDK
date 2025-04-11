package net.krusher.mortalsdk;

public class Range {
    private Integer from;
    private Integer to;

    public Range (Integer from, Integer to) {
        this.from = from;
        this.to = to;
    }

    public static Range of(Integer from, Integer to) {
        return new Range(from, to);
    }

    public Integer getFrom() {
        return from;
    }

    public void setFrom(Integer from) {
        this.from = from;
    }

    public Integer getTo() {
        return to;
    }

    public void setTo(Integer to) {
        this.to = to;
    }

    public boolean isInRange(int i) {
        return i >= from && i <= to;
    }
}
