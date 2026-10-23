package com.recon.service.importer;

import com.recon.service.importer.DatasetImportService.ImportResult;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/imports")
@Tag(name = "Imports", description = "Load generated datasets")
public class ImportController {

    private final DatasetImportService importService;

    public ImportController(DatasetImportService importService) {
        this.importService = importService;
    }

    @PostMapping
    @Operation(summary = "Import the dataset in the configured import directory",
            description = "Loads instruments, counterparties, trades and confirms. Idempotent: rows already present (by ref) are skipped.")
    public ImportResult importDataset() {
        return importService.importDataset();
    }
}
