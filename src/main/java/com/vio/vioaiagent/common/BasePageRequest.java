package com.vio.vioaiagent.common;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.io.Serializable;

/**
 * 分页请求基类
 */
@Data
@Schema(description = "分页请求参数")
public class BasePageRequest implements Serializable {

    private static final long serialVersionUID = 1L;

    @Schema(description = "当前页码", example = "1")
    private Integer pageNum = 1;

    @Schema(description = "每页条数", example = "10")
    private Integer pageSize = 10;

    @Schema(description = "排序字段")
    private String sortField;

    @Schema(description = "排序方向: asc/desc", example = "desc")
    private String sortOrder;
}