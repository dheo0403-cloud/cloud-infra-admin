package com.example.infra;

import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import java.io.FileInputStream;
import java.io.File;

public class ReadTemplateTest {
    @Test
    public void testReadTemplate() {
        String path = "c:/JetBrains/workspace/cloud-infra-admin/backend/src/main/resources/templates/excel/gcp_audit_template.xlsm";
        try (FileInputStream fis = new FileInputStream(new File(path));
             Workbook workbook = new XSSFWorkbook(fis)) {
            
            Sheet sheet = workbook.getSheet("고객사점검표");
            if (sheet == null) {
                System.out.println("Sheet not found");
                return;
            }

            System.out.println("Printing details of row 70:");
            Row row = sheet.getRow(70);
            if (row != null) {
                Cell catCell = row.getCell(1);
                Cell labelCell = row.getCell(2);
                String cat = catCell != null ? catCell.toString() : "";
                String label = labelCell != null ? labelCell.toString() : "";
                
                System.out.println("Category: " + cat);
                System.out.println("Label: " + label);
                System.out.println("Label length: " + label.length());
                for (int i = 0; i < label.length(); i++) {
                    char c = label.charAt(i);
                    System.out.printf("Char %d: '%c' (Unicode: \\u%04x)%n", i, c, (int)c);
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}
