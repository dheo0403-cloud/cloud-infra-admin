package com.example.infra;

import com.example.infra.entity.InfraAuditReport;
import com.example.infra.entity.InfraEnvironment;
import com.google.cloud.bigquery.*;
import org.apache.poi.xslf.usermodel.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.example.infra.service.GcpResourceFetcher;
import com.google.auth.oauth2.GoogleCredentials;
import com.google.cloud.compute.v1.ForwardingRule;

public class PptxExportServiceTest {

    @Mock
    private BigQuery bigQuery;

    @BeforeEach
    public void setUp() {
        MockitoAnnotations.openMocks(this);
    }

    @Test
    public void testPrintForwardingRules() throws Exception {
        GcpResourceFetcher gcpResourceFetcher = new GcpResourceFetcher();
        GoogleCredentials credentials = GoogleCredentials.fromStream(
            new org.springframework.core.io.ClassPathResource("gcp-credentials.json").getInputStream()
        ).createScoped(List.of("https://www.googleapis.com/auth/cloud-platform"));

        List<String> projects = List.of(
            "ns-infr-host-402505", "prd-dfd", "wjis-gw-project", "ssycne",
            "hcompany-485701", "secu-390423", "skspecialty", "hcompanycsg",
            "ns-aiplatform-prd", "skshipping", "prd-pasta", "ns-aiplatform-dev", "infra-platform"
        );
        for (String pid : projects) {
            System.out.println("==========================================");
            System.out.println("PROJECT: " + pid);
            System.out.println("==========================================");
            try {
                List<ForwardingRule> rules = gcpResourceFetcher.getForwardingRules(credentials, pid);
                System.out.println("Found " + rules.size() + " forwarding rules.");
                for (int i = 0; i < rules.size(); i++) {
                    ForwardingRule rule = rules.get(i);
                    System.out.println("Rule #" + (i + 1) + ":");
                    System.out.println("  Name: " + rule.getName());
                    System.out.println("  Region: " + (rule.hasRegion() ? rule.getRegion() : "Global"));
                    System.out.println("  LoadBalancingScheme: " + (rule.hasLoadBalancingScheme() ? rule.getLoadBalancingScheme() : "null"));
                    System.out.println("  IPAddress: " + (rule.hasIPAddress() ? rule.getIPAddress() : "null"));
                    System.out.println("  IPProtocol: " + (rule.hasIPProtocol() ? rule.getIPProtocol() : "null"));
                    System.out.println("  PortRange: " + (rule.hasPortRange() ? rule.getPortRange() : "null"));
                    System.out.println("  Target: " + (rule.hasTarget() ? rule.getTarget() : "null"));
                    System.out.println("  BackendService: " + (rule.hasBackendService() ? rule.getBackendService() : "null"));
                }
            } catch (Exception e) {
                System.out.println("Error for " + pid + ": " + e.getMessage());
            }
        }
    }

    private FieldValueList createMockRow(String ym, String resourceType, long count) {
        FieldValueList mockRow = mock(FieldValueList.class);
        
        FieldValue ymVal = mock(FieldValue.class);
        when(ymVal.getStringValue()).thenReturn(ym);
        when(mockRow.get("ym")).thenReturn(ymVal);

        FieldValue typeVal = mock(FieldValue.class);
        when(typeVal.getStringValue()).thenReturn(resourceType);
        when(mockRow.get("resource_type")).thenReturn(typeVal);

        FieldValue countVal = mock(FieldValue.class);
        when(countVal.getLongValue()).thenReturn(count);
        when(mockRow.get("resource_count")).thenReturn(countVal);

        return mockRow;
    }

    @Test
    public void testPrintNewSlidesStructure() {
        String templatePath = "c:/JetBrains/workspace/cloud-infra-admin/backend/src/main/resources/templates/report/GCP_Report_Template.pptx";
        try (java.io.FileInputStream fis = new java.io.FileInputStream(new java.io.File(templatePath));
             XMLSlideShow ppt = new XMLSlideShow(fis)) {
            
            System.out.println("TOTAL SLIDES: " + ppt.getSlides().size());
            for (int i = 14; i < ppt.getSlides().size(); i++) {
                XSLFSlide slide = ppt.getSlides().get(i);
                System.out.println("--------------------------------------------------");
                System.out.println("SLIDE INDEX: " + i + " (Slide " + (i + 1) + ")");
                System.out.println("--------------------------------------------------");
                for (XSLFShape shape : slide.getShapes()) {
                    System.out.println("Shape Name: " + shape.getShapeName() + " | Type: " + shape.getClass().getName());
                    if (shape instanceof XSLFTextShape) {
                        XSLFTextShape ts = (XSLFTextShape) shape;
                        System.out.println("  Text: " + ts.getText());
                    }
                    if (shape instanceof XSLFTable) {
                        XSLFTable table = (XSLFTable) shape;
                        System.out.println("  Table (Rows: " + table.getNumberOfRows() + ", Cols: " + table.getNumberOfColumns() + ")");
                        System.out.println("  Anchor Y: " + table.getAnchor().getY());
                        for (int r = 0; r < table.getNumberOfRows(); r++) {
                            StringBuilder rowText = new StringBuilder();
                            for (int c = 0; c < table.getNumberOfColumns(); c++) {
                                XSLFTableCell cell = table.getCell(r, c);
                                rowText.append("[").append(cell != null ? cell.getText().trim() : "").append("]\t");
                            }
                            System.out.println("    Row " + r + ": " + rowText);
                        }
                    }
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}
