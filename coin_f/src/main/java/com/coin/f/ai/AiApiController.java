package com.coin.f.ai;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.coin.f.ai.AiReportService.Result;

@RestController
@RequestMapping("/api/ai")
public class AiApiController {

	private final AiReportService service;

	public AiApiController(AiReportService service) {
		this.service = service;
	}

	/** 리포트 즉시 재생성 (1분 쿨다운) */
	@PostMapping("/regenerate")
	public Result regenerate() {
		return service.regenerate();
	}
}
