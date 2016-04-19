package uk.co.oliwali.HawkEye.parser;

public interface ArgumentParser {

	char getParameterName();
	
	Criteria parse(String... arguments);
}
