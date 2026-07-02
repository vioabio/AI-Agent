package com.vio.vioaiagent.common;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.io.Serializable;
import java.util.Collections;
import java.util.List;

/**
 * 分页响应包装类
 *
 * @param <T> 列表元素类型
 */
@Data
@Schema(description = "分页响应")
public class PageResult<T> implements Serializable {

    private static final long serialVersionUID = 1L;

    @Schema(description = "当前页码")
    private Integer pageNum;

    @Schema(description = "每页条数")
    private Integer pageSize;

    @Schema(description = "总记录数")
    private Long total;

    @Schema(description = "总页数")
    private Integer totalPages;

    @Schema(description = "数据列表")
    private List<T> records;

    private PageResult() {
    }

    public static <T> PageResult<T> of(Integer pageNum, Integer pageSize, Long total, List<T> records) {
        PageResult<T> result = new PageResult<>();
        result.setPageNum(pageNum);
        result.setPageSize(pageSize);
        result.setTotal(total != null ? total : 0L);
        result.setTotalPages(pageSize != null && pageSize > 0
                ? (int) Math.ceil((double) result.getTotal() / pageSize)
                : 0);
        result.setRecords(records != null ? records : Collections.emptyList());
        return result;
    }

    public static <T> PageResult<T> empty() {
        return of(1, 10, 0L, Collections.emptyList());
    }
}