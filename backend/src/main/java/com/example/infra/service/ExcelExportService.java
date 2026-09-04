package com.example.infra.service;

import com.example.infra.entity.InfraAuditDetail;
import com.example.infra.entity.InfraAuditReport;
import com.example.infra.entity.InfraEnvironment;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.streaming.SXSSFWorkbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 점검 결과 엑셀 파일 생성 및 출력 서비스
 * 점검 완료 데이터를 Apache POI 라이브러리를 사용하여 지정된 엑셀 매크로(.xlsm) 템플릿 파일에 바인딩하고 파일 바이너리 데이터를 생성합니다.
 */
@Service
public class ExcelExportService {

    /**
     * 프로젝트별 점검 리포트 분할 및 엑셀 바이트 맵 생성
     * 수집된 점검 보고서 내부의 상세 점검(Details) 목록을 GCP 프로젝트 ID 별로 그룹화하여,
     * 각 프로젝트별로 엑셀 파일을 각각 생성한 뒤 프로젝트 ID를 키로 하는 맵 구조로 변환합니다.
     * 
     * @param report 마스터 점검 보고서 객체
     * @return 프로젝트 ID별로 생성된 엑셀 파일 바이너리(byte[]) 맵
     * @throws IOException 파일 I/O 오류 발생 시
     */
    public Map<String, byte[]> exportAuditReportsByProject(InfraAuditReport report) throws IOException {
        Map<String, byte[]> result = new java.util.HashMap<>();
        if (report.getDetails() == null || report.getDetails().isEmpty()) {
            result.put("default", exportAuditReport(report));
            return result;
        }

        Map<String, List<InfraAuditDetail>> projectDetails = report.getDetails().stream()
                .filter(d -> d.getProjectId() != null)
                .collect(Collectors.groupingBy(InfraAuditDetail::getProjectId));

        if (projectDetails.isEmpty()) {
            result.put("default", exportAuditReport(report));
            return result;
        }

        for (Map.Entry<String, List<InfraAuditDetail>> entry : projectDetails.entrySet()) {
            String projectId = entry.getKey();
            InfraAuditReport projectReport = new InfraAuditReport();
            projectReport.setEnvironment(report.getEnvironment());
            projectReport.setAuditDate(report.getAuditDate());
            projectReport.setDetails(entry.getValue());
            result.put(projectId, exportAuditReport(projectReport));
        }
        return result;
    }

    /**
     * 단일 점검 보고서 엑셀 바이너리 생성
     * 템플릿 폴더에서 클라우드 공급자 유형(GCP/Azure)에 알맞은 엑셀 서식을 로드하여 데이터를 입력합니다.
     * 템플릿 파일이 없는 경우 기본적인 새 통합 문서(SXSSFWorkbook)를 생성하여 동적으로 작성합니다.
     * 
     * @param report 점검 보고서 객체
     * @return 엑셀 파일 바이트 배열
     * @throws IOException 파일 로드 및 I/O 오류 발생 시
     */
    public byte[] exportAuditReport(InfraAuditReport report) throws IOException {
        InfraEnvironment env = report.getEnvironment();
        String provider = (env != null && env.getProviderType() != null) ? env.getProviderType() : "GCP";
        String templatePath = "templates/excel/" + (provider.equals("GCP") ? "gcp_audit_template.xlsm" : "azure_audit_template.xlsm");
        
        ClassPathResource resource = new ClassPathResource(templatePath);
        if (resource.exists()) {
            System.out.println("Template found: " + templatePath);
            try (InputStream is = resource.getInputStream();
                 XSSFWorkbook workbook = new XSSFWorkbook(is)) {
                
                Sheet sheet = workbook.getSheet("고객사점검표");
                if (sheet != null) {
                    fillTemplateAuditData(sheet, report);
                } else {
                    System.err.println("Sheet '고객사점검표' not found in template!");
                }
                
                // 파일이 열릴 때 수식이 다시 계산되도록 마크업 설정
                workbook.setForceFormulaRecalculation(true);

                ByteArrayOutputStream out = new ByteArrayOutputStream();
                workbook.write(out);
                return out.toByteArray();
            }
        }

        // 템플릿 파일이 부재한 경우 예외 복구(Fallback)용 기본 서식 엑셀 문서 생성
        try (SXSSFWorkbook workbook = new SXSSFWorkbook()) {
            Sheet sheet = workbook.createSheet("Audit Report - " + 
                (report.getEnvironment() != null ? report.getEnvironment().getEnvironmentName() : "Unknown"));

            // 스타일 정의
            CellStyle headerStyle = createHeaderStyle(workbook);
            CellStyle passStyle = createStatusStyle(workbook, IndexedColors.BRIGHT_GREEN);
            CellStyle failStyle = createStatusStyle(workbook, IndexedColors.RED);
            CellStyle warningStyle = createStatusStyle(workbook, IndexedColors.ORANGE);

            // 헤더 작성
            String[] headers = {"구분", "점검 항목", "상태", "점검 결과"};
            Row headerRow = sheet.createRow(0);
            for (int i = 0; i < headers.length; i++) {
                Cell cell = headerRow.createCell(i);
                cell.setCellValue(headers[i]);
                cell.setCellStyle(headerStyle);
            }

            // 점검 데이터 로우 추가
            int rowIdx = 1;
            for (InfraAuditDetail detail : report.getDetails()) {
                Row row = sheet.createRow(rowIdx++);
                row.createCell(0).setCellValue(detail.getCategory());
                row.createCell(1).setCellValue(detail.getItem());
                
                Cell statusCell = row.createCell(2);
                statusCell.setCellValue(detail.getStatus());
                if ("PASS".equalsIgnoreCase(detail.getStatus())) {
                    statusCell.setCellStyle(passStyle);
                } else if ("FAIL".equalsIgnoreCase(detail.getStatus())) {
                    statusCell.setCellStyle(failStyle);
                } else if ("WARNING".equalsIgnoreCase(detail.getStatus())) {
                    statusCell.setCellStyle(warningStyle);
                }
                
                row.createCell(3).setCellValue(detail.getResultText());
            }

            // 열 너비 강제 조정
            sheet.setColumnWidth(0, 6000);
            sheet.setColumnWidth(1, 8000);
            sheet.setColumnWidth(2, 3000);
            sheet.setColumnWidth(3, 12000);

            ByteArrayOutputStream out = new ByteArrayOutputStream();
            workbook.write(out);
            return out.toByteArray();
        }
    }

    /**
     * 엑셀 템플릿 시트에 점검 데이터 자동 매핑 및 기입
     * 엑셀 파일의 정규화된 중분류 및 소분류(점검항목) 이름을 매칭하여 매핑 타겟 로우를 찾습니다.
     * 여러 인스턴스에 걸쳐 중복 수집된 데이터는 동일 항목에 대해 줄바꿈(---\n)으로 병합하여 누락 없이 기록합니다.
     * 성능 저하 방지를 위해 엑셀 전체 로우를 한 번만 순회하여 메모리에 카테고리/항목 이름을 캐싱(1차 인덱싱)한 뒤 매칭 처리를 수행합니다.
     * 
     * @param sheet  데이터를 작성할 엑셀 시트
     * @param report 바인딩할 점검 데이터 리포트
     */
    private void fillTemplateAuditData(Sheet sheet, InfraAuditReport report) {
        // 컬럼 고정 위치 설정
        int categoryColIdx = 1;      // B열 (중분류)
        int labelColIdx = 2;         // C열 (상세 점검 항목)
        int checkResultColIdx = 5;   // F열 (점검 결과 - 상태 기입)
        int resultTextColIdx = 6;    // G열 (점검 내용 - 상세 내용 기입)

        // 1. 공통 헤더 정보 입력 (G2 셀에 고객사 정보 기록)
        String custName = (report.getEnvironment() != null && report.getEnvironment().getCustomer() != null)
            ? report.getEnvironment().getCustomer().getName() : "알 수 없음";
        setCell(sheet, 1, 6, custName); 
        
        // 2. 수집된 다중 상세 점검 데이터 병합 처리 (중분류|||소분류 키 기준)
        Map<String, InfraAuditDetail> mergedDetails = new java.util.LinkedHashMap<>();
        for (InfraAuditDetail detail : report.getDetails()) {
            String key = detail.getCategory() + "|||" + detail.getItem();
            if (mergedDetails.containsKey(key)) {
                InfraAuditDetail existing = mergedDetails.get(key);
                existing.setCheckResult(existing.getCheckResult() + "\n---\n" + detail.getCheckResult());
                existing.setResultText(existing.getResultText() + "\n\n---\n\n" + detail.getResultText());
                existing.setRemediation(existing.getRemediation() + "\n\n---\n\n" + detail.getRemediation());
            } else {
                InfraAuditDetail newDetail = new InfraAuditDetail();
                newDetail.setCategory(detail.getCategory());
                newDetail.setItem(detail.getItem());
                newDetail.setCheckResult(detail.getCheckResult());
                newDetail.setResultText(detail.getResultText());
                newDetail.setRemediation(detail.getRemediation());
                mergedDetails.put(key, newDetail);
            }
        }
        
        // --- OPTIMIZATION START ---
        // 매 매칭 시 엑셀 시트의 전체 행을 탐색하며 공백/정규식 처리를 돌리는 구조적 병목 제거
        // 엑셀 시트를 딱 1번만 읽어 정규화된 텍스트 인덱스 리스트를 구축합니다.
        class RowData {
            int rowIdx;
            String normCategory;
            String normItem;
            RowData(int r, String c, String i) { rowIdx = r; normCategory = c; normItem = i; }
        }
        
        List<RowData> sheetRows = new java.util.ArrayList<>();
        for (int i = 0; i <= sheet.getLastRowNum(); i++) {
            Row row = sheet.getRow(i);
            if (row != null) {
                Cell catCell = row.getCell(categoryColIdx);
                Cell itemCell = row.getCell(labelColIdx);
                
                String c = (catCell != null && catCell.getCellType() == CellType.STRING) ? catCell.getStringCellValue().trim() : "";
                String it = (itemCell != null && itemCell.getCellType() == CellType.STRING) ? itemCell.getStringCellValue().trim() : "";
                
                if (!it.isEmpty()) {
                    String normC = c.replaceAll("[\\s\\u00a0]+", "").toLowerCase();
                    String normIt = it.replaceAll("[\\s\\u00a0]+", "").toLowerCase();
                    sheetRows.add(new RowData(i, normC, normIt));
                }
            }
        }
        // --- OPTIMIZATION END ---

        for (InfraAuditDetail detail : mergedDetails.values()) {
            String normCat = detail.getCategory() != null ? detail.getCategory().replaceAll("[\\s\\u00a0]+", "").toLowerCase() : "";
            String normItem = detail.getItem() != null ? detail.getItem().replaceAll("[\\s\\u00a0]+", "").toLowerCase() : "";
            if (normItem.isEmpty()) continue;

            int targetRow = -1;
            
            // 1단계: 정규화된 텍스트로 완전 동일 일치(Exact Match)하는 행 탐색
            for (RowData rd : sheetRows) {
                if (rd.normItem.equals(normItem)) {
                    if (normCat.isEmpty() || rd.normCategory.equals(normCat) || rd.normCategory.contains(normCat) || normCat.contains(rd.normCategory)) {
                        targetRow = rd.rowIdx;
                        break;
                    }
                }
            }
            
            // 2단계: 완전 일치 실패 시 부분 일치(Fuzzy Contains Match)로 유사 탐색 시도
            if (targetRow == -1) {
                for (RowData rd : sheetRows) {
                    boolean isLoggingCollision = (rd.normItem.contains("로깅") || normItem.contains("로깅")) && !rd.normItem.equals(normItem);
                    if (!isLoggingCollision && (rd.normItem.contains(normItem) || normItem.contains(rd.normItem))) {
                        if (normCat.isEmpty() || rd.normCategory.equals(normCat) || rd.normCategory.contains(normCat) || normCat.contains(rd.normCategory)) {
                            targetRow = rd.rowIdx;
                            break;
                        }
                    }
                }
            }
            
            // 매칭된 행이 발견된 경우 해당 행의 결과 열에 데이터 입력
            if (targetRow != -1) {
                setCell(sheet, targetRow, checkResultColIdx, detail.getCheckResult());
                setCell(sheet, targetRow, resultTextColIdx, detail.getResultText());
            } else {
                System.out.println("Excel Matching Failed for: [" + detail.getCategory() + "] " + detail.getItem());
            }
        }
    }

    /**
     * 특정 셀에 값을 안전하게 입력 및 초기화
     * Null Safe하게 Row 및 Cell을 생성하고 데이터 값을 입력합니다.
     * 
     * @param sheet  대상 시트
     * @param rowIdx 행 인덱스 (0-based)
     * @param colIdx 열 인덱스 (0-based)
     * @param value  셀에 기입할 문자열 값
     */
    private void setCell(Sheet sheet, int rowIdx, int colIdx, String value) {
        Row row = sheet.getRow(rowIdx);
        if (row == null) row = sheet.createRow(rowIdx);
        Cell cell = row.getCell(colIdx);
        if (cell == null) cell = row.createCell(colIdx);
        cell.setCellValue(value);
    }

    /**
     * Fallback 엑셀 생성용 테이블 헤더 스타일 정의
     * 
     * @param workbook 생성 중인 워크북
     * @return 스타일 서식
     */
    private CellStyle createHeaderStyle(Workbook workbook) {
        CellStyle style = workbook.createCellStyle();
        style.setFillForegroundColor(IndexedColors.LIGHT_CORNFLOWER_BLUE.getIndex());
        style.setFillPattern(FillPatternType.SOLID_FOREGROUND);
        style.setBorderBottom(BorderStyle.THIN);
        Font font = workbook.createFont();
        font.setBold(true);
        style.setFont(font);
        return style;
    }

    /**
     * Fallback 엑셀 생성용 점검 상태 텍스트 색상 서식 지정
     * 
     * @param workbook 생성 중인 워크북
     * @param color    글꼴에 부여할 인덱스 컬러
     * @return 스타일 서식
     */
    private CellStyle createStatusStyle(Workbook workbook, IndexedColors color) {
        CellStyle style = workbook.createCellStyle();
        Font font = workbook.createFont();
        font.setColor(color.getIndex());
        font.setBold(true);
        style.setFont(font);
        return style;
    }
}
