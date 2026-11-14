package db.engine;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.fasterxml.jackson.databind.type.TypeFactory;
import db.model.*;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;

public class Database {
    private final Path directory;
    private final Map<String,Table> tables = new LinkedHashMap<String,Table>();
    private final Map<String,Map<String,TreeMap<String,List<Integer>>>> indexes =
        new HashMap<String,Map<String,TreeMap<String,List<Integer>>>>();
    private final ObjectMapper mapper = new ObjectMapper();

    public Database(Path directory) { this.directory=directory; this.mapper.registerModule(new JavaTimeModule()); }

    public Collection<Table> tables() { return tables.values(); }
    public Table table(String name) {
        Table t=tables.get(name.toLowerCase());
        if (t==null) throw new IllegalArgumentException("Table not found: "+name);
        return t;
    }
    public void createTable(Table t) {
        if (tables.containsKey(t.name)) throw new IllegalArgumentException("Table already exists: "+t.name);
        tables.put(t.name,t);
    }
    public void dropTable(String name) {
        String n=name.toLowerCase();
        for (Table t: tables.values()) for (Column c:t.columns)
            if (c.foreignKey && n.equals(c.referencesTable)) throw new IllegalArgumentException("Table is referenced by "+t.name);
        if (tables.remove(n)==null) throw new IllegalArgumentException("Table not found: "+n);
        indexes.remove(n);
    }
    public void insert(Table t, Map<String,Object> row) {
        for (Column c:t.columns) if (!row.containsKey(c.name)) throw new IllegalArgumentException("Missing value for "+c.name);
        Column pk=null;
        for (Column c:t.columns) if (c.primaryKey) pk=c;
        if (pk!=null) for (Map<String,Object> old:t.rows)
            if (Objects.equals(old.get(pk.name),row.get(pk.name))) throw new IllegalArgumentException("Duplicate primary key: "+row.get(pk.name));
        for (Column c:t.columns) if (c.foreignKey) {
            Table parent=table(c.referencesTable);
            boolean found=false;
            for (Map<String,Object> p:parent.rows) if (Objects.equals(p.get(c.referencesColumn),row.get(c.name))) found=true;
            if (!found) throw new IllegalArgumentException("Foreign key violation: "+c.name);
        }
        t.rows.add(new LinkedHashMap<String,Object>(row));
        rebuildIndexes(t);
    }
    public void deleteRows(Table t, List<Map<String,Object>> matches) {
        t.rows.removeAll(matches); rebuildIndexes(t);
    }
    public void rebuildIndexes(Table t) {
        Map<String,TreeMap<String,List<Integer>>> byColumn=indexes.get(t.name);
        if (byColumn==null) return;
        for (String col:byColumn.keySet()) {
            TreeMap<String,List<Integer>> map=new TreeMap<String,List<Integer>>();
            for (int i=0;i<t.rows.size();i++) {
                Object value=t.rows.get(i).get(col);
                String key=key(value);
                if (!map.containsKey(key)) map.put(key,new ArrayList<Integer>());
                map.get(key).add(i);
            }
            byColumn.put(col,map);
        }
    }
    public void createIndex(String table, String column) {
        Table t=table(table); t.column(column);
        Map<String,TreeMap<String,List<Integer>>> byColumn=indexes.get(t.name);
        if (byColumn==null) { byColumn=new HashMap<String,TreeMap<String,List<Integer>>>(); indexes.put(t.name,byColumn); }
        if (byColumn.containsKey(column.toLowerCase())) throw new IllegalArgumentException("Index already exists");
        byColumn.put(column.toLowerCase(),new TreeMap<String,List<Integer>>());
        rebuildIndexes(t);
    }
    public List<Map<String,Object>> indexedEquals(Table t, String column, Object value) {
        Map<String,TreeMap<String,List<Integer>>> byColumn=indexes.get(t.name);
        if (byColumn==null || !byColumn.containsKey(column.toLowerCase())) return null;
        List<Integer> ids=byColumn.get(column.toLowerCase()).get(key(value));
        List<Map<String,Object>> result=new ArrayList<Map<String,Object>>();
        if (ids!=null) for (Integer id:ids) if (id<t.rows.size()) result.add(t.rows.get(id));
        return result;
    }
    private String key(Object value) { return value==null ? "null" : value.toString(); }

    public void load() throws IOException {
        Files.createDirectories(directory);
        try (DirectoryStream<Path> stream=Files.newDirectoryStream(directory,"*.json")) {
            for (Path p:stream) {
                if (p.getFileName().toString().endsWith(".index.json")) continue;
                Table t=mapper.readValue(p.toFile(),Table.class);
                tables.put(t.name,t);
            }
        }
        try (DirectoryStream<Path> stream=Files.newDirectoryStream(directory,"*.index.json")) {
            for (Path p:stream) {
                Map<String,Object> saved=mapper.readValue(p.toFile(), new TypeReferenceMap());
                String table=(String)saved.get("table"), column=(String)saved.get("column");
                if (tables.containsKey(table)) createIndex(table,column);
            }
        }
    }
    public void save() throws IOException {
        Files.createDirectories(directory);
        for (Table t:tables.values()) mapper.writerWithDefaultPrettyPrinter().writeValue(directory.resolve(t.name+".json").toFile(),t);
        for (String table:indexes.keySet()) for (String col:indexes.get(table).keySet()) {
            Map<String,String> info=new HashMap<String,String>(); info.put("table",table); info.put("column",col);
            mapper.writerWithDefaultPrettyPrinter().writeValue(directory.resolve(table+"_"+col+".index.json").toFile(),info);
        }
    }
    private static class TypeReferenceMap extends com.fasterxml.jackson.core.type.TypeReference<Map<String,Object>> {}
}
