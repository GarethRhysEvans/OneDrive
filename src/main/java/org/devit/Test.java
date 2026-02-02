package org.devit;

import com.opencsv.CSVReader;
import com.opencsv.CSVWriter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.FileReader;
import java.io.FileWriter;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

public class Test {
    private static final Logger logger = LoggerFactory.getLogger(Test.class);

    public static void main(String[] args) {

        try {

            OneDrive onedrive = new OneDrive();

            // Download File
            onedrive.download("Test", "test.csv", ".");

            // Load the CSV file
            CSVReader reader = new CSVReader(new FileReader("test.csv"));
            List<String[]> allRows = reader.readAll();
            reader.close();

            DateTimeFormatter formatter =
                    DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

            // No rows then add a default one
            if(allRows.isEmpty()) {
                String[] header = {"Date", "Run"};
                allRows.add(0, header);

                String[] data = { LocalDateTime.now().format(formatter), "0" };
                allRows.add(1, data);

            } else {

                // Otherwise, get the data row
                String[] data = allRows.get(1);

                // Get the first column value and increment
                int value = Integer.parseInt(data[1]); // first column

                data[0] =  LocalDateTime.now().format(formatter);
                data[1] = String.valueOf(value + 1);

                // Update rows
                allRows.set(1, data);
            }

            // Write updated CSV
            CSVWriter writer = new CSVWriter(new FileWriter("test.csv"));
            writer.writeAll(allRows);
            writer.close();

            // Upload the csv file
            onedrive.upload("./test.csv", "Test");

        } catch (Exception e) {
            logger.error("Test Error", e);
        }
    }


}
