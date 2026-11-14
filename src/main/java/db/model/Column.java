package db.model;

public class Column {
    public String name;
    public DataType type;
    public boolean primaryKey;
    public boolean foreignKey;
    public String referencesTable;
    public String referencesColumn;

    public Column() {}
    public Column(String name, DataType type) { this.name=name.toLowerCase(); this.type=type; }
}
