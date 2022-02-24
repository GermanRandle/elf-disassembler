# elf-disassembler
Given an ELF file. Supported ISA: RISC-V (RV32I, RV32M, RVC). A program outputs .text and .symtable sections for the file.

## For running
To run the program, you will need Java 11.

1) Put an ELF file in src.

2) Do command:

javac Main.java

3) Do command:

java Main <input_file_name> <output_file_name>
