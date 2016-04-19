package uk.co.oliwali.HawkEye.parser;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentSkipListSet;

public class Parser {

	private ConcurrentSkipListSet<ArgumentParser> parsers;
	
	public Parser() {
		this.parsers = new ConcurrentSkipListSet<>(new Comparator<ArgumentParser>() {

			@Override
			public int compare(ArgumentParser o1, ArgumentParser o2) {
				return o2.getParameterName() - o1.getParameterName();
			}
		});
	}

	public void registerParser(ArgumentParser parser) {
		this.parsers.add(parser);
	}
	
	public Criteria parseArguments(String... commandArguments) {
		Map<Character,List<String>> arguments;
		for (String argument : commandArguments) {
			String[] split = argument.split(":",1);
			
		}
		return null;
	}
}
