package com.ap0stole.sheetsmith.controller;

import com.ap0stole.sheetsmith.domain.dto.JobHistoryDto;
import com.ap0stole.sheetsmith.services.JobService;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.Resource;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/history")
@RequiredArgsConstructor
public class HistoryController {

    private final JobService jobService;

    @GetMapping
    public ResponseEntity<Page<JobHistoryDto>> getHistory(
            @PageableDefault(size = 20, sort = "createdAt", direction = Sort.Direction.DESC) Pageable pageable) {
        return ResponseEntity.ok(jobService.getHistory(pageable));
    }

    @GetMapping("/{id}")
    public ResponseEntity<JobHistoryDto> getJob(@PathVariable Long id) {
        return ResponseEntity.ok(jobService.getById(id));
    }

    @GetMapping("/{id}/download")
    public ResponseEntity<Resource> downloadResult(@PathVariable Long id) {
        Resource file = jobService.downloadResult(id);
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.parseMediaType(
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"));
        headers.setContentDisposition(ContentDisposition.attachment()
                .filename(file.getFilename())
                .build());
        return ResponseEntity.ok().headers(headers).body(file);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteJob(@PathVariable Long id) {
        jobService.deleteJob(id);
        return ResponseEntity.noContent().build();
    }
}
