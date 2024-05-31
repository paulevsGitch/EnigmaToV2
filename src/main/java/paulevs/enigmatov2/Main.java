package paulevs.enigmatov2;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

public class Main {
	private static final String EMPTY = "";
	private static final String TABS = "\t\t\t";
	private static final Map<String, String> EMPTY_MAP = new HashMap<>();
	
	public static void main(String[] args) throws IOException {
		if (args.length < 3 || args.length > 4) {
			System.out.println("Usage: enigmatov2 <input_folder> <output_folder> <intermediary-v2.tiny> [optional_exclude.txt]");
			return;
		}
		
		File dirIn = new File(args[0]);
		File dirOut = new File(args[1]);
		
		if (!dirIn.exists()) {
			System.out.println("Input directory doesn't exist");
			return;
		}
		
		if (!dirOut.exists()) {
			//noinspection ResultOfMethodCallIgnored
			dirOut.mkdirs();
		}
		
		Map<String, Map<String, String>> intermediaryFields = new HashMap<>();
		Map<String, String> intermediaryClasses = new HashMap<>();
		
		List<String> intermediaryText = getLines(new File(args[2]));
		List<String> header = Arrays.stream(intermediaryText.get(0).split("\t")).toList();
		intermediaryText.remove(0);
		
		int intermediary = header.indexOf("intermediary") - 2;
		int glue = header.indexOf("glue") - 2;
		int client = header.indexOf("client") - 2;
		int server = header.indexOf("server") - 2;
		int fIntermediary = intermediary + 1;
		int fGlue = glue + 1;
		int fClient = client + 1;
		int fServer = server + 1;
		
		String className = null;
		for (String line : intermediaryText) {
			String[] parts = line.trim().split("\t");
			if (line.startsWith("c")) {
				className = parts[intermediary];
				String glueName = parts.length > glue ? parts[glue] : EMPTY;
				String clientName = parts.length > client ? parts[client] : EMPTY;
				String serverName = parts.length > server ? parts[server] : EMPTY;
				intermediaryClasses.put(className, "\t" + glueName + "\t" + serverName + "\t" + clientName);
			}
			else if (className != null) {
				String fieldName = parts[fIntermediary];
				String glueName = parts.length > fGlue ? parts[fGlue] : EMPTY;
				String clientName = parts.length > fClient ? parts[fClient] : EMPTY;
				String serverName = parts.length > fServer ? parts[fServer] : EMPTY;
				if ((clientName.equals(glueName) || serverName.equals(glueName)) && !className.contains("argo")) {
					System.out.println(className + " " + fieldName + " " + glueName + " " + clientName + " " + serverName);
				}
				intermediaryFields.computeIfAbsent(className, k -> new HashMap<>()).put(
					fieldName, "\t" + glueName + "\t" + serverName + "\t" + clientName
				);
			}
		}
		
		Map<String, ClassMapping> mappings = new HashMap<>();
		readMappings(dirIn, mappings, intermediaryClasses, intermediaryFields);
		
		if (args.length > 3) {
			Set<String> exclude = getLines(new File(args[3])).stream().collect(Collectors.toUnmodifiableSet());
			if (!exclude.isEmpty()) {
				List<String> excludeClasses = new ArrayList<>();
				mappings.forEach((key, c) -> {
					exclude.forEach(c.fieldMappings::remove);
					exclude.forEach(c.methodsMappings::remove);
					if (exclude.contains(c.className)) {
						excludeClasses.add(key);
					}
				});
				excludeClasses.forEach(mappings::remove);
			}
		}
		
		StringBuilder builder = new StringBuilder("tiny\t2\t0\tintermediary\tnamed\tglue\tserver\tclient\n");
		mappings.values().stream().filter(ClassMapping::isValid).forEach(c -> builder.append(c.asString(0)));
		FileWriter writer = new FileWriter(new File(dirOut, "mappings.tiny"));
		writer.write(builder.toString());
		writer.flush();
		writer.close();
	}
	
	private static void readMappings(
		File dir, Map<String, ClassMapping> mappings,
		Map<String, String> intermediaryClasses, Map<String, Map<String, String>> intermediaryFields
	) {
		for (File file : dir.listFiles()) {
			if (file.isDirectory()) readMappings(file, mappings, intermediaryClasses, intermediaryFields);
			else if (file.getName().endsWith(".mapping")) {
				ClassMapping c1 = getMapping(getLines(file), new AtomicInteger(), null, intermediaryClasses, intermediaryFields);
				ClassMapping c2 = mappings.get(c1.className);
				if (c2 != null) c1 = mergeClasses(c1, c2);
				mappings.put(c1.className, c1);
			}
		}
	}
	
	private static List<String> getLines(File file) {
		List<String> lines;
		try {
			lines = Files.readAllLines(file.toPath());
		}
		catch (IOException e) {
			throw new RuntimeException(e);
		}
		return lines;
	}
	
	private static ClassMapping getMapping(List<String> lines, AtomicInteger index, ClassMapping parent, Map<String, String> intermediaryClasses, Map<String, Map<String, String>> intermediaryFields) {
		String line = lines.get(index.getAndIncrement());
		int startingTabs = getTabs(line);
		String[] parts = line.trim().split(" ");
		
		String mapName = parts.length > 2 ? parts[2] : parts[1];
		
		if (parent != null) {
			mapName = parent.classMapping.substring(0, parent.classMapping.indexOf('\t')) + "$" + (parts.length > 2 ? parts[2] : parts[1]);
			parts[1] = parent.className + "$" + parts[1];
		}
		
		mapName += intermediaryClasses.getOrDefault(parts[1], EMPTY);
		
		ClassMapping classMapping = new ClassMapping(parts[1], mapName);
		MethodMapping activeMethod = null;
		
		while (index.get() < lines.size()) {
			line = lines.get(index.get());
			
			int tabs = getTabs(line);
			if (tabs <= startingTabs) return classMapping;
			
			line = line.trim();
			parts = line.split(" ");
			
			switch (parts[0]) {
				case "CLASS" -> {
					ClassMapping nestedClass = getMapping(lines, index, classMapping,
						intermediaryClasses,
						intermediaryFields
					);
					ClassMapping contained = classMapping.nested.get(parts[1]);
					if (contained != null) {
						nestedClass = mergeClasses(contained, nestedClass);
					}
					classMapping.nested.put(parts[1], nestedClass);
				}
				case "FIELD" -> {
					String name = intermediaryFields.getOrDefault(classMapping.className, EMPTY_MAP).getOrDefault(parts[1], TABS);
					if (!parts[1].equals(parts[2])) {
						classMapping.fieldMappings.put(
							parts[1],
							"\tf\t" + parts[3] + "\t" + parts[1] + "\t" + parts[2] + name
						);
					}
				}
				case "METHOD" -> {
					String name = intermediaryFields.getOrDefault(classMapping.className, EMPTY_MAP).getOrDefault(parts[1], TABS);
					if (parts.length > 3 && !parts[1].equals(parts[2])) {
						activeMethod = new MethodMapping(parts[1],
							"\tm\t" + parts[3] + "\t" + parts[1] + "\t" + parts[2] + name
						);
						name = parts[1].startsWith("method_") ? parts[1] : parts[1] + " " + line;
						classMapping.methodsMappings.put(name, activeMethod);
					}
					else if (parts[1].equals("<init>")) {
						activeMethod = new MethodMapping(parts[1],
							"\tm\t" + parts[2] + "\t<init>\t<init>\t<init>\t<init>\t<init>"
						);
						classMapping.methodsMappings.put(parts[1] + " " + parts[2], activeMethod);
					}
				}
				case "ARG" -> {
					if (activeMethod != null && !parts[1].equals(parts[2])) {
						activeMethod.args.put(Integer.parseInt(parts[1]), "\t\tp\t" + parts[1] + "\t\t" + parts[2] + "\t\t\t");
					}
				}
			}
			
			index.incrementAndGet();
		}
		
		return classMapping;
	}
	
	private static ClassMapping mergeClasses(ClassMapping a, ClassMapping b) {
		String mapName = a.classMapping.equals(a.className) ? b.classMapping : a.classMapping;
		ClassMapping result = new ClassMapping(a.className, mapName);
		
		mergeMaps(a.nested, b.nested, result.nested);
		mergeMaps(a.fieldMappings, b.fieldMappings, result.fieldMappings);
		
		a.methodsMappings.forEach((name, mapping1) -> {
			MethodMapping mapping2 = b.methodsMappings.get(name);
			if (mapping2 != null) {
				MethodMapping resultMapping = new MethodMapping(mapping1.methodName, mapping1.methodString);
				mergeMaps(mapping1.args, mapping2.args, resultMapping.args);
				result.methodsMappings.put(name, resultMapping);
			}
		});
		mergeMaps(a.methodsMappings, b.methodsMappings, result.methodsMappings);
		
		return result;
	}
	
	private static <K, V> void mergeMaps(Map<K, V> a, Map<K, V> b, Map<K, V> out) {
		a.forEach((k, v) -> { if (!out.containsKey(k)) out.put(k, v); });
		b.forEach((k, v) -> { if (!out.containsKey(k)) out.put(k, v); });
	}
	
	private static int getTabs(String line) {
		int count = 0;
		while (count < line.length() && line.charAt(count) == '\t') count++;
		return count;
	}
	
	private static class ClassMapping {
		final String className;
		final String classMapping;
		
		final Map<String, String> fieldMappings = new HashMap<>();
		final Map<String, MethodMapping> methodsMappings = new HashMap<>();
		final Map<String, ClassMapping> nested = new HashMap<>();
		
		ClassMapping(String className, String classMapping) {
			this.className = className;
			this.classMapping = classMapping;
		}
		
		public boolean isValid() {
			return !(classMapping.equals(className) && fieldMappings.isEmpty() && methodsMappings.isEmpty() && nested.isEmpty());
		}
		
		public String asString(int tabs) {
			StringBuilder builder = new StringBuilder();
			builder.append("c\t");
			builder.append(className);
			builder.append('\t');
			builder.append(classMapping);
			builder.append('\n');
			
			fieldMappings.values().stream().sorted().forEach(field -> {
				builder.append(field);
				builder.append('\n');
			});
			
			methodsMappings
				.values()
				.stream()
				.sorted(Comparator.comparing(m -> m.methodName))
				.forEach(m -> builder.append(m.asString(tabs)));
			
			nested.values().stream().filter(ClassMapping::isValid).forEach(c -> builder.append(c.asString(tabs + 1)));
			
			return builder.toString();
		}
	}
	
	private static class MethodMapping {
		final String methodName;
		final String methodString;
		
		final Map<Integer, String> args = new HashMap<>();
		
		MethodMapping(String methodName, String methodString) {
			this.methodName = methodName;
			this.methodString = methodString;
		}
		
		public String asString(int tabs) {
			final StringBuilder builder = new StringBuilder(methodString);
			builder.append('\n');
			args.keySet().stream().sorted().forEach(key -> {
				builder.append("\t".repeat(Math.max(0, tabs)));
				builder.append(args.get(key));
				builder.append('\n');
			});
			return builder.toString();
		}
	}
}