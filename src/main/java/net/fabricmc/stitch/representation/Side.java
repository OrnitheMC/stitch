package net.fabricmc.stitch.representation;

public enum Side {
    ANY, CLIENT, SERVER, ALL;

    public boolean isIn(Side that) {
        return this == that || this == ANY || that == ALL;
    }
}
