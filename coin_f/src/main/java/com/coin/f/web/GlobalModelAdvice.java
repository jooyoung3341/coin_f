package com.coin.f.web;

import java.util.List;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ModelAttribute;

/**
 * 모든 화면에 공통으로 필요한 메뉴/종목 목록을 모델에 넣어준다.
 */
@ControllerAdvice(assignableTypes = PageController.class)
public class GlobalModelAdvice {

	@Value("${app.market.symbols}")
	private List<String> symbols;

	@ModelAttribute("menus")
	public Menu[] menus() {
		return Menu.values();
	}

	@ModelAttribute("symbols")
	public List<String> symbols() {
		return symbols;
	}
}
