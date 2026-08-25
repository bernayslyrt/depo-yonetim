package com.depo.service;

import java.io.ByteArrayInputStream;
import java.io.IOException;

public interface ReportService {

    ByteArrayInputStream exportProductsToExcel() throws IOException;

    ByteArrayInputStream exportProductsToPdf() throws IOException;
}
