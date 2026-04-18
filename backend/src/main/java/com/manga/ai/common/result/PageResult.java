package com.manga.ai.common.result;

import lombok.Data;

import java.io.Serializable;
import java.util.List;

/**
 * 分页结果
 */
@Data
public class PageResult<T> implements Serializable {

    private static final long serialVersionUID = 1L;

    private List<T> records;
    private Long total;
    private Long size;
    private Long current;
    private Long pages;

    public static <T> PageResult<T> of(List<T> records, Long total, Long size, Long current) {
        PageResult<T> result = new PageResult<>();
        result.setRecords(records);
        result.setTotal(total);
        result.setSize(size);
        result.setCurrent(current);
        result.setPages((total + size - 1) / size);
        return result;
    }
}
