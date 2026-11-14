package db.model;

import java.util.*;

public class Table {
    public String name;
    public List<Column> columns = new ArrayList<Column>();
    public List<Map<String,Object>> rows = new ArrayList<Map<String,Object>>();

    public Table() {}
    public Table(String name, List<Column> columns) {
        this.name=name.toLowerCase(); this.columns=columns;
        for (Column c: columns) c.name=c.name.toLowerCase();
    }
    public Column column(String name) {
        for (Column c: columns) if (c.name.equalsIgnoreCase(name)) return c;
        throw new IllegalArgumentException("Unknown column: " + name);
    }
    public boolean hasColumn(String name) {
        for (Column c: columns) if (c.name.equalsIgnoreCase(name)) return true;
        return false;
    }
}
