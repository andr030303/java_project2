package db.sql;

import db.engine.Database;
import db.model.*;
import java.time.LocalDate;
import java.util.*;
import java.util.regex.*;

/** Small SQL-like interpreter. Supports simple single-condition statements. */
public class SqlEngine {
    private final Database db;
    public SqlEngine(Database db) { this.db=db; }

    public String execute(String sql) {
        String s=sql.trim();
        if (s.endsWith(";")) s=s.substring(0,s.length()-1).trim();
        String upper=s.toUpperCase(Locale.ROOT);
        if (upper.startsWith("CREATE TABLE ")) return createTable(s);
        if (upper.startsWith("DROP TABLE ")) { db.dropTable(s.substring(11).trim()); return "Table dropped."; }
        if (upper.startsWith("CREATE INDEX ")) return createIndex(s);
        if (upper.startsWith("INSERT INTO ")) return insert(s);
        if (upper.startsWith("SELECT ")) return select(s);
        if (upper.startsWith("UPDATE ")) return update(s);
        if (upper.startsWith("DELETE FROM ")) return delete(s);
        throw new IllegalArgumentException("Unsupported SQL statement");
    }

    private String createTable(String s) {
        Matcher m=Pattern.compile("(?is)CREATE TABLE (\\w+)\\s*\\((.*)\\)").matcher(s);
        if(!m.matches()) throw new IllegalArgumentException("Invalid CREATE TABLE syntax");
        List<Column> cols=new ArrayList<Column>();
        for(String part:splitComma(m.group(2))) {
            String[] a=part.trim().split("\\s+");
            if(a.length<2) throw new IllegalArgumentException("Invalid column definition: "+part);
            Column c=new Column(a[0],parseType(a[1]));
            String rest=part.toUpperCase(Locale.ROOT);
            c.primaryKey=rest.contains("PRIMARY KEY");
            Matcher fk=Pattern.compile("(?i)REFERENCES\\s+(\\w+)\\s*\\(\\s*(\\w+)\\s*\\)").matcher(part);
            if(fk.find()){ c.foreignKey=true; c.referencesTable=fk.group(1).toLowerCase(); c.referencesColumn=fk.group(2).toLowerCase(); }
            cols.add(c);
        }
        db.createTable(new Table(m.group(1),cols)); return "Table created.";
    }
    private DataType parseType(String t) {
        String x=t.toUpperCase(Locale.ROOT);
        if(x.equals("INT")||x.equals("INTEGER")) return DataType.INTEGER;
        if(x.equals("DOUBLE")||x.equals("FLOAT")||x.equals("REAL")) return DataType.DOUBLE;
        if(x.equals("VARCHAR")||x.equals("STRING")||x.equals("TEXT")) return DataType.STRING;
        if(x.equals("DATE")) return DataType.DATE;
        if(x.equals("BOOLEAN")||x.equals("BOOL")) return DataType.BOOLEAN;
        throw new IllegalArgumentException("Unsupported type: "+t);
    }
    private String createIndex(String s) {
        Matcher m=Pattern.compile("(?i)CREATE INDEX\\s+\\w+\\s+ON\\s+(\\w+)\\s*\\(\\s*(\\w+)\\s*\\)").matcher(s);
        if(!m.matches()) throw new IllegalArgumentException("Invalid CREATE INDEX syntax");
        db.createIndex(m.group(1),m.group(2)); return "Index created.";
    }
    private String insert(String s) {
        Matcher m=Pattern.compile("(?is)INSERT INTO\\s+(\\w+)\\s*(?:\\((.*?)\\))?\\s+VALUES\\s*\\((.*)\\)").matcher(s);
        if(!m.matches()) throw new IllegalArgumentException("Invalid INSERT syntax");
        Table t=db.table(m.group(1));
        List<String> names=new ArrayList<String>();
        if(m.group(2)==null) for(Column c:t.columns) names.add(c.name);
        else for(String n:splitComma(m.group(2))) names.add(n.trim().toLowerCase());
        List<String> vals=splitComma(m.group(3));
        if(names.size()!=vals.size()) throw new IllegalArgumentException("Column/value count mismatch");
        Map<String,Object> row=new LinkedHashMap<String,Object>();
        for(int i=0;i<names.size();i++) row.put(names.get(i),convert(t.column(names.get(i)).type,vals.get(i)));
        db.insert(t,row); return "1 row inserted.";
    }
    private String select(String s) {
        Matcher m=Pattern.compile("(?is)SELECT\\s+(.*?)\\s+FROM\\s+(\\w+)(?:\\s+WHERE\\s+(.*))?").matcher(s);
        if(!m.matches()) throw new IllegalArgumentException("Invalid SELECT syntax");
        Table t=db.table(m.group(2));
        List<Map<String,Object>> rows=filtered(t,m.group(3));
        List<String> cols=new ArrayList<String>();
        if(m.group(1).trim().equals("*")) for(Column c:t.columns) cols.add(c.name);
        else for(String c:splitComma(m.group(1))) { t.column(c.trim()); cols.add(c.trim().toLowerCase()); }
        StringBuilder out=new StringBuilder();
        for(String c:cols) out.append(c).append("\t");
        out.append("\n");
        for(Map<String,Object> row:rows) { for(String c:cols) out.append(row.get(c)).append("\t"); out.append("\n"); }
        out.append(rows.size()).append(" row(s).");
        return out.toString();
    }
    private String update(String s) {
        Matcher m=Pattern.compile("(?is)UPDATE\\s+(\\w+)\\s+SET\\s+(\\w+)\\s*=\\s*(.*?)\\s+WHERE\\s+(.*)").matcher(s);
        if(!m.matches()) throw new IllegalArgumentException("Invalid UPDATE syntax");
        Table t=db.table(m.group(1)); String col=m.group(2).toLowerCase();
        Object value=convert(t.column(col).type,m.group(3));
        List<Map<String,Object>> rows=filtered(t,m.group(4));
        for(Map<String,Object> row:rows) row.put(col,value);
        // Re-check uniqueness and foreign keys by rebuilding through a validation pass.
        validateTable(t); db.rebuildIndexes(t);
        return rows.size()+" row(s) updated.";
    }
    private String delete(String s) {
        Matcher m=Pattern.compile("(?is)DELETE FROM\\s+(\\w+)(?:\\s+WHERE\\s+(.*))?").matcher(s);
        if(!m.matches()) throw new IllegalArgumentException("Invalid DELETE syntax");
        Table t=db.table(m.group(1)); List<Map<String,Object>> rows=filtered(t,m.group(2));
        db.deleteRows(t,rows); return rows.size()+" row(s) deleted.";
    }
    private void validateTable(Table t) {
        for(int i=0;i<t.rows.size();i++) for(int j=i+1;j<t.rows.size();j++)
            for(Column c:t.columns) if(c.primaryKey && Objects.equals(t.rows.get(i).get(c.name),t.rows.get(j).get(c.name)))
                throw new IllegalArgumentException("Duplicate primary key after UPDATE");
        for(Column c:t.columns) if(c.foreignKey) for(Map<String,Object> r:t.rows) {
            boolean found=false; for(Map<String,Object> p:db.table(c.referencesTable).rows)
                if(Objects.equals(p.get(c.referencesColumn),r.get(c.name))) found=true;
            if(!found) throw new IllegalArgumentException("Foreign key violation");
        }
    }
    private List<Map<String,Object>> filtered(Table t,String condition) {
        if(condition==null || condition.trim().isEmpty()) return new ArrayList<Map<String,Object>>(t.rows);
        Matcher m=Pattern.compile("(?is)(\\w+)\\s*(=|!=|<>|>=|<=|>|<)\\s*(.+)").matcher(condition.trim());
        if(!m.matches()) throw new IllegalArgumentException("Unsupported WHERE condition");
        String col=m.group(1).toLowerCase(), op=m.group(2); Column c=t.column(col);
        Object expected=convert(c.type,m.group(3));
        List<Map<String,Object>> candidates=null;
        if(op.equals("=")) candidates=db.indexedEquals(t,col,expected);
        if(candidates==null) candidates=t.rows;
        List<Map<String,Object>> result=new ArrayList<Map<String,Object>>();
        for(Map<String,Object> row:candidates) if(compare(row.get(col),expected,op)) result.add(row);
        return result;
    }
    @SuppressWarnings({"unchecked","rawtypes"})
    private boolean compare(Object a,Object b,String op) {
        int cmp=((Comparable)a).compareTo(b);
        if(op.equals("=")) return cmp==0;
        if(op.equals("!=")||op.equals("<>")) return cmp!=0;
        if(op.equals(">")) return cmp>0;
        if(op.equals("<")) return cmp<0;
        if(op.equals(">=")) return cmp>=0;
        return cmp<=0;
    }
    private Object convert(DataType type,String raw) {
        String v=raw.trim();
        if(v.startsWith("'")&&v.endsWith("'")) v=v.substring(1,v.length()-1).replace("''","'");
        switch(type) {
            case INTEGER: return Integer.valueOf(v);
            case DOUBLE: return Double.valueOf(v);
            case BOOLEAN: if(v.equalsIgnoreCase("TRUE")) return Boolean.TRUE; if(v.equalsIgnoreCase("FALSE")) return Boolean.FALSE; throw new IllegalArgumentException("Expected TRUE/FALSE");
            case DATE: return LocalDate.parse(v);
            default: return v;
        }
    }
    private List<String> splitComma(String s) {
        List<String> out=new ArrayList<String>(); StringBuilder b=new StringBuilder(); boolean quote=false;
        for(int i=0;i<s.length();i++){ char ch=s.charAt(i); if(ch=='\'') quote=!quote; if(ch==','&&!quote){out.add(b.toString().trim());b.setLength(0);}else b.append(ch); }
        if(b.length()>0) out.add(b.toString().trim()); return out;
    }
}
