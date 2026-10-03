package org.worldgit.hub.web;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.*;
import org.worldgit.core.normalize.DecodeBudget;

class ApiErrorTest {
  @RestController static class Download {
    @GetMapping("/download") void download(HttpServletResponse response) throws Exception {
      response.setContentType("application/zip");
      response.setHeader("Content-Security-Policy", "default-src 'none'");
      throw new DecodeBudget.Exceeded("下載大小");
    }
  }
  @Test void downloadBudgetErrorIsJsonEvenWithZipContentType() throws Exception {
    MockMvcBuilders.standaloneSetup(new Download()).setControllerAdvice(new ApiError()).build()
        .perform(get("/download"))
        .andExpect(status().isPayloadTooLarge())
        .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
        .andExpect(header().string("Content-Security-Policy", "default-src 'none'"))
        .andExpect(jsonPath("$.code").value("budget"));
  }
}
