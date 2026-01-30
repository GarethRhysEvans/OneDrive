package org.devit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class Test {
    private static final Logger logger = LoggerFactory.getLogger(Test.class);

    public static void main(String[] args) {

        try {
            new OneDrive().new DriveWalker().list("root");
        } catch (Exception e) {
            logger.error("Test Error", e);
        }
    }


}
