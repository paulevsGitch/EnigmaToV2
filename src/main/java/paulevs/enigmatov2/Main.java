package paulevs.enigmatov2;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

public class Main {
	public static void main(String[] args) throws IOException {
		if (args.length != 2) {
			System.out.println("Usage: enigmatov2 <input_folder> <output_folder>");
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
		
		Map<String, ClassMapping> mappings = new HashMap<>();
		readMappings(dirIn, mappings);
		StringBuilder builder = new StringBuilder("tiny\t2\t0\tintermediary\tnamed\n");
		mappings.values().forEach(c -> builder.append(c.asString(0)));
		FileWriter writer = new FileWriter(new File(dirOut, "mappings.tiny"));
		writer.write(builder.toString());
		writer.flush();
		writer.close();
	}
	
	private static void readMappings(File dir, Map<String, ClassMapping> mappings) {
		for (File file : dir.listFiles()) {
			if (file.isDirectory()) readMappings(file, mappings);
			else if (file.getName().endsWith(".mapping")) {
				ClassMapping c = readMappingFile(file);
				ClassMapping c2 = mappings.get(c.className);
				if (c2 != null) c = mergeClasses(c, c2);
				mappings.put(c.className, c);
			}
		}
	}
	
	private static ClassMapping readMappingFile(File file) {
		List<String> lines;
		try {
			lines = Files.readAllLines(file.toPath());
		}
		catch (IOException e) {
			throw new RuntimeException(e);
		}
		return getMapping(lines, new AtomicInteger(), null);
	}
	
	private static ClassMapping getMapping(List<String> lines, AtomicInteger index, ClassMapping parent) {
		String line = lines.get(index.getAndIncrement());
		int startingTabs = getTabs(line);
		String[] parts = line.trim().split(" ");
		
		if (parent != null) {
			parts[1] = parent.className + "$" + parts[1];
			if (parts.length > 2) {
				parts[2] = parent.classMapping + "$" + parts[2];
			}
		}
		
		String className = parts.length > 2 ? parts[2] : parts[1];
		ClassMapping mapping = new ClassMapping(parts[1], className);
		MethodMapping activeMethod = null;
		
		while (index.get() < lines.size()) {
			line = lines.get(index.get());
			
			int tabs = getTabs(line);
			if (tabs <= startingTabs) return mapping;
			
			line = line.trim();
			parts = line.split(" ");
			
			switch (parts[0]) {
				case "CLASS" -> {
					ClassMapping nestedClass = getMapping(lines, index, mapping);
					ClassMapping contained = mapping.nested.get(parts[1]);
					if (contained != null) {
						nestedClass = mergeClasses(contained, nestedClass);
					}
					mapping.nested.put(parts[1], nestedClass);
				}
				case "FIELD" -> {
					if (!parts[1].equals(parts[2])) {
						mapping.fieldMappings.put(parts[1], "\tf\t" + parts[3] + "\t" + parts[1] + "\t" + parts[2]);
					}
				}
				case "METHOD" -> {
					if (parts.length > 3 && !parts[1].equals(parts[2])) {
						activeMethod = new MethodMapping(parts[1],
							"\tm\t" + parts[3] + "\t" + parts[1] + "\t" + parts[2]
						);
						String name = parts[1].startsWith("method_") ? parts[1] : parts[1] + " " + line;
						mapping.methodsMappings.put(name, activeMethod);
					}
				}
				case "ARG" -> {
					if (activeMethod != null && !parts[1].equals(parts[2])) {
						activeMethod.args.put(Integer.parseInt(parts[1]), "\t\tp\t" + parts[1] + "\t\t" + parts[2]);
					}
				}
			}
			
			index.incrementAndGet();
		}
		
		return mapping;
	}
	
	private static ClassMapping mergeClasses(ClassMapping a, ClassMapping b) {
		String className = a.classMapping.equals(a.className) ? b.classMapping : a.classMapping;
		ClassMapping result = new ClassMapping(a.className, className);
		
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
		
		String getFileName() {
			return classMapping.replace("/.", "/") + ".mapping";
		}
		
		public String asString(int tabs) {
			if (className.equals(classMapping) && fieldMappings.isEmpty() && methodsMappings.isEmpty()) {
				return "";
			}
			
			StringBuilder builder = new StringBuilder();
			builder.append("c\t");
			builder.append(className);
			if (!classMapping.substring(classMapping.lastIndexOf('/') + 1).startsWith("class_")) {
				builder.append('\t');
				builder.append(classMapping);
			}
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
			
			nested.values().forEach(c -> builder.append(c.asString(tabs + 1)));
			
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