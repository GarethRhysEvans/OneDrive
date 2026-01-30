package org.devit;

import com.opencsv.CSVReader;
import com.opencsv.CSVWriter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.FileReader;
import java.io.FileWriter;
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

            // No rows then add a default one
            if(allRows.isEmpty()) {
                String[] newRow = {"1", "Test"}; // default values
                allRows.add(0, newRow); // insert at top
            } else {

                // Otherwise, get the first row columns
                String[] cols = allRows.get(0);

                // Get the first column value and increment
                int value = Integer.parseInt(cols[0]); // first column

                cols[0] = String.valueOf(value + 1);

                // Update rows
                allRows.set(0, cols);
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
