package db;

import db.engine.Database;
import db.sql.SqlEngine;
import java.nio.file.Paths;
import java.util.Scanner;

public class Main {
    public static void main(String[] args) throws Exception {
        Database database = new Database(Paths.get("data"));
        database.load();
        SqlEngine engine = new SqlEngine(database);
        System.out.println("SimpleDB (Java 8+) — enter SQL commands or EXIT;");
        System.out.println("Example: SELECT * FROM cars;");
        Scanner scanner = new Scanner(System.in);
        StringBuilder statement = new StringBuilder();
        while (true) {
            System.out.print(statement.length() == 0 ? "db> " : "   > ");
            if (!scanner.hasNextLine()) break;
            String line = scanner.nextLine().trim();
            if (line.equalsIgnoreCase("EXIT") || line.equalsIgnoreCase("QUIT")) break;
            statement.append(line).append(' ');
            if (!line.endsWith(";")) continue;
            try {
                String result = engine.execute(statement.toString().trim());
                if (result != null && !result.isEmpty()) System.out.println(result);
            } catch (RuntimeException ex) {
                System.out.println("ERROR: " + ex.getMessage());
            }
            statement.setLength(0);
        }
        database.save();
        System.out.println("Database saved. Bye.");
    }
}
