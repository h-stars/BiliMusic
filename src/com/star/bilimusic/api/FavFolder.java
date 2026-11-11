package com.star.bilimusic.api;

/** 收藏夹。 */
public class FavFolder {
    public long id;
    public String title = "";
    public int mediaCount = 0;
    public boolean isDefault = false;

    public FavFolder(long id, String title, int count, boolean def) {
        this.id = id;
        this.title = title;
        this.mediaCount = count;
        this.isDefault = def;
    }
}
