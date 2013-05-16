package casmi.db;

import casmi.db.Entity;


public class Alcohol extends Entity {

    private String name;
    private int    abv;
    public  String origin;

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public int getAbv() {
        return abv;
    }

    public void setAbv(int abv) {
        this.abv = abv;
    }
}
