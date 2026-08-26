package com.ap0stole.sheetsmith.domain.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class ImproveByPathRequest {

    @NotBlank
    private String inputPath;

    @NotBlank
    private String outputPath;

    @NotBlank
    @Size(max = 2000)
    private String instruction;
}
