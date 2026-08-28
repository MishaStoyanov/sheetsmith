package com.ap0stole.sheetsmith.domain.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

/**
 * The scripting entry point that names files instead of uploading them.
 *
 * <p>Both paths must resolve — symlinks followed — inside a configured root, and the endpoint is
 * off unless it was deliberately switched on. It reads and writes the server's own filesystem,
 * which is useful for a scheduled job and dangerous anywhere else.
 */
@Getter
@Setter
public class ImproveByPathRequest {

    /** The workbook to read, inside one of the configured roots. */

    @NotBlank
    private String inputPath;

    /** Where to write the result. Its directory must exist and be inside a root as well. */
    @NotBlank
    private String outputPath;

    /** What to do, in the same plain language the upload flow takes. */
    @NotBlank
    @Size(max = 2000)
    private String instruction;
}
