import java.text.ParseException;
import java.util.HashMap;
import java.util.Map;

public class ElfParser extends ByteParser {
    private long e_shoff;
    private long e_shentsize;
    private long e_shnum;
    private long e_shstrndx;
    private long shstr_offset;
    private long strtab_offset;
    private long textBegin;
    private long pc;
    private final Map<Long, String> addrToName = new HashMap<>();

    private static String TO_STT(long num) {
        switch ((int) num) {
            case 0:
                return "NOTYPE";
            case 1:
                return "OBJECT";
            case 2:
                return "FUNC";
            case 3:
                return "SECTION";
            case 4:
                return "FILE";
            default:
                if (13 <= num && num <= 15) {
                    return "PROC";
                }
                return "UNKNOWN_TYPE";
        }
    }

    private static String TO_STB(long num) {
        switch ((int) num) {
            case 0:
                return "LOCAL";
            case 1:
                return "GLOBAL";
            case 2:
                return "WEAK";
            default:
                if (13 <= num && num <= 15) {
                    return "PROC";
                }
                return "UNKNOWN_BIND";
        }
    }

    private static String TO_STV(long num) {
        switch ((int) num) {
            case 0:
                return "DEFAULT";
            case 1:
                return "INTERNAL";
            case 2:
                return "HIDDEN";
            case 3:
                return "PROTECTED";
            default:
                return "UNKNOWN_VALUE";
        }
    }

    private static String TO_SHN(long num) {
        if (num == 0) {
            return "UNDEF";
        } else if (num == 0xff00L) {
            return "BEFORE";
        } else if (num == 0xff01L) {
            return "AFTER";
        } else if (num == 0xfff1L) {
            return "ABS";
        } else if (num == 0xfff2L) {
            return "COMMON";
        } else if (num == 0xffffL) {
            return "XINDEX";
        }
        return Long.toString(num);
    }

    private static String TO_REG(long num) {
        switch ((int) num) {
            case 0:
                return "zero";
            case 1:
                return "ra";
            case 2:
                return "sp";
            case 3:
                return "gp";
            case 4:
                return "tp";
            case 5:
                return "t0";
            case 6:
            case 7:
                return "t" + (num - 5);
            case 8:
                return "s0";
            case 9:
                return "s1";
            case 10:
            case 11:
            case 12:
            case 13:
            case 14:
            case 15:
            case 16:
            case 17:
                return "a" + (num - 10);
            case 18:
            case 19:
            case 20:
            case 21:
            case 22:
            case 23:
            case 24:
            case 25:
            case 26:
            case 27:
                return "s" + (num - 16);
            case 28:
            case 29:
            case 30:
            case 31:
                return "t" + (num - 25);
            default:
                return "unkown_reg";
        }
    }

    private static String TO_CREG(long num) {
        return TO_REG(num + 8);
    }

    private static String TO_CSR(long num) {
        if (num == 0x001L) {
            return "fflags";
        } else if (num == 0x002L) {
            return "frm";
        } else if (num == 0x003L) {
            return "fcsr";
        } else if (num == 0xc00L) {
            return "cycle";
        } else if (num == 0xc01L) {
            return "time";
        } else if (num == 0xc02L) {
            return "instret";
        } else if (num == 0xc80L) {
            return "cycleh";
        } else if (num == 0xc81L) {
            return "timeh";
        } else if (num == 0xc82L) {
            return "instreth";
        }
        return "unknown_csr_reg";
    }

    private final static String unknown_command = "unknown_command";

    public ElfParser(ByteSource source) {
        super(source);
    }

    public String parse() throws ParseException {
        parseHeader();
        return parseSectionHeaderTable();
    }

    private void parseHeader() throws ParseException {
        moveTo(0);
        expectMagic();
        expect(1); // EI_CLASS = 32-bit file
        expect(1); // EI_DATA = little endian
        expect(1); // EI_VERSION = 1
        moveTo(32);
        e_shoff = read4();
        moveTo(46);
        e_shentsize = read2();
        e_shnum = read2();
        e_shstrndx = read2();
    }

    private String parseSectionHeaderTable() throws ParseException {
        moveTo(e_shoff + e_shstrndx * e_shentsize + 16);
        shstr_offset = read4();
        moveTo(e_shoff);
        // Finding .text, .symtab and .strtab
        long symtabPos = 0;
        long symtabSize = 0;
        long textPos = 0;
        long textSize = 0;
        for (int i = 0; i < e_shnum; i++) {
            long sh_name = read4();
            long sh_type = read4();
            if (sh_type == 2 && ".symtab".equals(getSectionName(sh_name))) {
                jumpOver(8);
                symtabPos = read4();
                symtabSize = read4();
                jumpOver(16);
                continue;
            } else if (sh_type == 1 && ".text".equals(getSectionName(sh_name))) {
                jumpOver(4);
                textBegin = read4();
                textPos = read4();
                textSize = read4();
                jumpOver(16);
                continue;
            } else if (sh_type == 3 && ".strtab".equals(getSectionName(sh_name))) {
                jumpOver(8);
                strtab_offset = read4();
                jumpOver(20);
                continue;
            }
            jumpOver(32);
        }
        StringBuilder result = new StringBuilder();
        String symTabView;
        if (symtabPos != 0) {
            symTabView = parseSymbolTable(symtabPos, symtabSize);
        } else {
            symTabView = "";
        }
        result.append(".text\n");
        if (textPos != 0) {
            result.append(parseText(textPos, textSize));
        }
        result.append("\n.symtab\n").append(symTabView);
        return result.toString();
    }

    private String parseText(long textPos, long textSize) throws ParseException {
        long prevPos = getPos();
        moveTo(textPos);
        StringBuilder sb = new StringBuilder();
        pc = textBegin;
        for (int i = 0; i < textSize; i += 2) {
            long first = read2();
            if ((first & 3) == 3) {
                long second = read2();
                i += 2;
                String command = parseRV32IOrM((second << 16) | first);
                String name = addrToName.getOrDefault(textBegin + i - 2, "");
                if (name.length() > 0) {
                    sb.append(String.format("%08x %10s: %s\n", textBegin + i - 2, name, command));
                } else {
                    sb.append(String.format("%08x %11s %s\n", textBegin + i - 2, name, command));
                }
                pc += 4;
            } else {
                String command = parseRVC(first);
                String name = addrToName.getOrDefault(textBegin + i, "");
                if (name.length() > 0) {
                    sb.append(String.format("%08x %10s: %s\n", textBegin + i, name, command));
                } else {
                    sb.append(String.format("%08x %11s %s\n", textBegin + i, name, command));
                }
                pc += 2;
            }
        }
        moveTo(prevPos);
        return sb.toString();
    }

    private String parseSymbolTable(long symtabPos, long symtabSize) throws ParseException {
        long prevPos = getPos();
        moveTo(symtabPos);
        StringBuilder symtab = new StringBuilder();
        symtab.append(String.format("%s %-15s %7s %-8s %-8s %-8s %6s %s\n", "Symbol", "Value", "Size",
                "Type", "Bind", "Vis", "Index", "Name"));
        for (int i = 0; i < symtabSize; i += 16) {
            long st_name = read4();
            long st_value = read4();
            long st_size = read4();
            long st_info = read();
            long st_other = read();
            long st_shndx = read2();

            long st_bind = st_info >> 4;
            long st_type = st_info & 0xf;
            String index = TO_SHN(st_shndx);
            String name = getName(st_name);

            symtab.append(String.format("[%4d] 0x%-15s %5d %-8s %-8s %-8s %6s %s\n", i / 16, Long.toHexString(st_value),
                    st_size, TO_STT(st_type), TO_STB(st_bind), TO_STV(st_other & 3), index, name));

            if (TO_STT(st_type).equals("FUNC")) {
                addrToName.put(st_value, name);
            }
        }
        moveTo(prevPos);
        return symtab.toString();
    }

    String getName(long offset) throws ParseException {
        long prevPos = getPos();
        moveTo(strtab_offset);
        jumpOver(offset);
        StringBuilder name = new StringBuilder();
        do {
            name.append((char) read());
        } while (name.charAt(name.length() - 1) != 0);
        name.deleteCharAt(name.length() - 1);
        moveTo(prevPos);
        return name.toString();
    }

    String getSectionName(long offset) throws ParseException {
        long prevPos = getPos();
        moveTo(shstr_offset);
        jumpOver(offset);
        StringBuilder name = new StringBuilder();
        do {
            name.append((char) read());
        } while (name.charAt(name.length() - 1) != 0);
        name.deleteCharAt(name.length() - 1);
        moveTo(prevPos);
        return name.toString();
    }

    private void expectMagic() throws ParseException {
        // Magic 7f 45 4c 46
        expect(0x7f);
        expect(0x45);
        expect(0x4c);
        expect(0x46);
    }

    private String parseRV32IOrM(long mask) {
        long opcode = bitSubstr(mask, 6, 0);
        long funct7 = bitSubstr(mask, 31, 25);
        if (opcode == 0b0110011 && funct7 == 1) {
            return parseRV32M(mask);
        }
        return parseRV32I(mask);
    }

    private long bitSubstr(long value, int r, int l) {
        long andMask = (1L << (r + 1)) - 1 - ((1L << l) - 1);
        return (andMask & value) >> l;
    }

    private String normalView(String[] command) {
        StringBuilder sb = new StringBuilder();
        if (command.length == 1) {
            return command[0];
        }
        sb.append(command[0]).append(' ');
        for (int i = 1; i < command.length - 1; i++) {
            sb.append(command[i]).append(", ");
        }
        sb.append(command[command.length - 1]);
        return sb.toString();
    }

    private String normalViewAddr(String[] command) {
        StringBuilder sb = new StringBuilder();
        sb.append(command[0]).append(' ');
        for (int i = 1; i < command.length - 2; i++) {
            sb.append(command[i]).append(", ");
        }
        if (command.length > 2) {
            sb.append(command[command.length - 2]).append(", ");
        }
        long addr = Long.parseLong(command[command.length - 1], 16);
        sb.append(addrToName.getOrDefault(addr, String.format("LOC_%05x", addr)));
        return sb.toString();
    }

    private String loadStoreView(String[] command) {
        StringBuilder sb = new StringBuilder();
        sb.append(command[0]).append(' ');
        for (int i = 1; i < command.length - 2; i++) {
            sb.append(command[i]).append(", ");
        }
        sb.append(command[command.length - 2]).append('(').append(command[command.length - 1]).append(')');
        return sb.toString();
    }

    private String parseRV32I(long mask) {
        long rd = bitSubstr(mask, 11, 7);
        long rs1 = bitSubstr(mask, 19, 15);
        long rs2 = bitSubstr(mask, 24, 20);
        long funct7 = bitSubstr(mask,31, 25);
        long funct3 = bitSubstr(mask, 14, 12);
        long opcode = bitSubstr(mask, 6, 0);
        long imm110 = bitSubstr(mask, 31, 20);
        String rdReg = TO_REG(rd);
        String rs1Reg = TO_REG(rs1);
        String rs2Reg = TO_REG(rs2);
        String offset;
        switch ((int) opcode) {
            case 0b0110011:
                if (funct7 == 0) {
                    switch ((int) funct3) {
                        case 0b000:
                            return normalView(new String[]{"add", rdReg, rs1Reg, rs2Reg});
                        case 0b001:
                            return normalView(new String[]{"sll", rdReg, rs1Reg, rs2Reg});
                        case 0b010:
                            return normalView(new String[]{"slt", rdReg, rs1Reg, rs2Reg});
                        case 0b011:
                            return normalView(new String[]{"sltu", rdReg, rs1Reg, rs2Reg});
                        case 0b100:
                            return normalView(new String[]{"xor", rdReg, rs1Reg, rs2Reg});
                        case 0b101:
                            return normalView(new String[]{"srl", rdReg, rs1Reg, rs2Reg});
                        case 0b110:
                            return normalView(new String[]{"or", rdReg, rs1Reg, rs2Reg});
                        case 0b111:
                            return normalView(new String[]{"and", rdReg, rs1Reg, rs2Reg});
                        default:
                            return unknown_command;
                    }
                } else if (funct7 == (1L << 5)) {
                    if (funct3 == 0b000) {
                        return normalView(new String[]{"sub", rdReg, rs1Reg, rs2Reg});
                    } else if (funct3 == 0b101) {
                        return normalView(new String[]{"sra", rdReg, rs1Reg, rs2Reg});
                    }
                    return unknown_command;
                } else {
                    return unknown_command;
                }
            case 0b0010011:
                switch ((int) funct3) {
                    case 0b000:
                        return normalView(new String[]{"addi", rdReg, rs1Reg, getImmediateI(mask)});
                    case 0b010:
                        return normalView(new String[]{"slti", rdReg, rs1Reg, getImmediateI(mask)});
                    case 0b011:
                        return normalView(new String[]{"sltiu", rdReg, rs1Reg, getImmediateI(mask)});
                    case 0b100:
                        return normalView(new String[]{"xori", rdReg, rs1Reg, getImmediateI(mask)});
                    case 0b110:
                        return normalView(new String[]{"ori", rdReg, rs1Reg, getImmediateI(mask)});
                    case 0b111:
                        return normalView(new String[]{"andi", rdReg, rs1Reg, getImmediateI(mask)});
                    case 0b001:
                        if (funct7 == 0) {
                            return normalView(new String[]{"slli", rdReg, rs1Reg, Long.toString(rs2)});
                        }
                        return unknown_command;
                    case 0b101:
                        if (funct7 == (1L << 5)) {
                            return normalView(new String[]{"srai", rdReg, rs1Reg, Long.toString(rs2)});
                        } else if (funct7 == 0) {
                            return normalView(new String[]{"srli", rdReg, rs1Reg, Long.toString(rs2)});
                        }
                        return unknown_command;
                    default:
                        return unknown_command;
                }
            case 0b0100011:
                switch ((int) funct3) {
                    case 0b000:
                        return loadStoreView(new String[]{"sb", rs2Reg, getImmediateS(mask), rs1Reg});
                    case 0b001:
                        return loadStoreView(new String[]{"sh", rs2Reg, getImmediateS(mask), rs1Reg});
                    case 0b010:
                        return loadStoreView(new String[]{"sw", rs2Reg, getImmediateS(mask), rs1Reg});
                    default:
                        return unknown_command;
                }
            case 0b0000011:
                switch ((int) funct3) {
                    case 0b000:
                        return loadStoreView(new String[]{"lb", rdReg, getImmediateI(mask), rs1Reg});
                    case 0b001:
                        return loadStoreView(new String[]{"lh", rdReg, getImmediateI(mask), rs1Reg});
                    case 0b010:
                        return loadStoreView(new String[]{"lw", rdReg, getImmediateI(mask), rs1Reg});
                    case 0b100:
                        return loadStoreView(new String[]{"lbu", rdReg, getImmediateI(mask), rs1Reg});
                    case 0b101:
                        return loadStoreView(new String[]{"lhu", rdReg, getImmediateI(mask), rs1Reg});
                    default:
                        return unknown_command;
                }
            case 0b1100011:
                offset = Long.toHexString(pc + Long.parseLong(getImmediateB(mask)));
                switch ((int) funct3) {
                    case 0b000:
                        return normalViewAddr(new String[]{"beq", rs1Reg, rs2Reg, offset});
                    case 0b001:
                        return normalViewAddr(new String[]{"bne", rs1Reg, rs2Reg, offset});
                    case 0b100:
                        return normalViewAddr(new String[]{"blt", rs1Reg, rs2Reg, offset});
                    case 0b101:
                        return normalViewAddr(new String[]{"bge", rs1Reg, rs2Reg, offset});
                    case 0b110:
                        return normalViewAddr(new String[]{"bltu", rs1Reg, rs2Reg, offset});
                    case 0b111:
                        return normalViewAddr(new String[]{"bgeu", rs1Reg, rs2Reg, offset});
                    default:
                        return unknown_command;
                }
            case 0b1100111:
                if (funct3 == 0b000) {
                    return loadStoreView(new String[]{"jalr", rdReg, getImmediateI(mask), rs1Reg});
                }
                return unknown_command;
            case 0b1101111:
                offset = Long.toHexString(pc + Long.parseLong(getImmediateJ(mask)));
                return normalViewAddr(new String[]{"jal", rdReg, offset});
            case 0b0010111:
                return normalView(new String[]{"auipc", rdReg, getImmediateU(mask)});
            case 0b0110111:
                return normalView(new String[]{"lui", rdReg, getImmediateU(mask)});
            case 0b1110011:
                switch ((int) funct3) {
                    case 0b000:
                        if (rs1 == 0 && rd == 0) {
                            if (imm110 == 0) {
                                return normalView(new String[]{"ecall"});
                            } else if (imm110 == 1) {
                                return normalView(new String[]{"ebreak"});
                            }
                            return unknown_command;
                        }
                    case 0b001:
                        return normalView(new String[]{"csrrw", rdReg, TO_CSR(imm110), rs1Reg});
                    case 0b010:
                        return normalView(new String[]{"csrrs", rdReg, TO_CSR(imm110), rs1Reg});
                    case 0b011:
                        return normalView(new String[]{"csrrc", rdReg, TO_CSR(imm110), rs1Reg});
                    case 0b101:
                        return normalView(new String[]{"csrrwi", rdReg, TO_CSR(imm110), Long.toString(rs1)});
                    case 0b110:
                        return normalView(new String[]{"csrrsi", rdReg, TO_CSR(imm110), Long.toString(rs1)});
                    case 0b111:
                        return normalView(new String[]{"csrrci", rdReg, TO_CSR(imm110), Long.toString(rs1)});
                }
            default:
                return unknown_command;
        }
    }

    private String getImmediateI(long mask) {
        long res = 0;
        if (bitSubstr(mask, 31, 31) > 0) {
            res = 0b011_111_111_111_111_111_111_000_000_000_000L;
        }
        res |= bitSubstr(mask, 31, 20);
        if (bitSubstr(res, 31, 31) > 0) {
            res -= (1L << 32);
        }
        return Long.toString(res);
    }

    private String getImmediateS(long mask) {
        long res = 0;
        if (bitSubstr(mask, 31, 31) > 0) {
            res = 0b011_111_111_111_111_111_111_000_000_000_000L;
        }
        res |= (bitSubstr(mask, 31, 25) << 5);
        res |= bitSubstr(mask, 11, 7);
        if (bitSubstr(res, 31, 31) > 0) {
            res -= (1L << 32);
        }
        return Long.toString(res);
    }

    private String getImmediateB(long mask) {
        long res = 0;
        if (bitSubstr(mask, 31, 31) > 0) {
            res = 0b011_111_111_111_111_111_111_000_000_000_000L;
        }
        res |= (bitSubstr(mask, 7, 7) << 11);
        res |= (bitSubstr(mask, 30, 25) << 5);
        res |= (bitSubstr(mask, 11, 8) << 1);
        if (bitSubstr(res, 31, 31) > 0) {
            res -= (1L << 32);
        }
        return Long.toString(res);
    }

    private String getImmediateU(long mask) {
        long res = bitSubstr(mask, 31, 12) << 12;
        if (bitSubstr(res, 31, 31) > 0) {
            res -= (1L << 32);
        }
        return Long.toString(res);
    }

    private String getImmediateJ(long mask) {
        long res = 0;
        if (bitSubstr(mask, 31, 31) > 0) {
            res = 0b011_111_111_111_100_000_000_000_000_000_000L;
        }
        res |= (bitSubstr(mask, 19, 12) << 12);
        res |= (bitSubstr(mask, 20, 20) << 11);
        res |= (bitSubstr(mask, 30, 25) << 5);
        res |= (bitSubstr(mask, 24, 21) << 1);
        if (bitSubstr(res, 31, 31) > 0) {
            res -= (1L << 32);
        }
        return Long.toString(res);
    }

    private String parseRV32M(long mask) {
        long funct3 = bitSubstr(mask,14, 12);
        long rd = bitSubstr(mask, 11, 7);
        long rs1 = bitSubstr(mask, 19, 15);
        long rs2 = bitSubstr(mask, 24, 20);
        String rdReg = TO_REG(rd);
        String rs1Reg = TO_REG(rs1);
        String rs2Reg = TO_REG(rs2);
        switch ((int) funct3) {
            case 0:
                return normalView(new String[]{"mul", rdReg, rs1Reg, rs2Reg});
            case 1:
                return normalView(new String[]{"mulh", rdReg, rs1Reg, rs2Reg});
            case 2:
                return normalView(new String[]{"mulhsu", rdReg, rs1Reg, rs2Reg});
            case 3:
                return normalView(new String[]{"mulhu", rdReg, rs1Reg, rs2Reg});
            case 4:
                return normalView(new String[]{"div", rdReg, rs1Reg, rs2Reg});
            case 5:
                return normalView(new String[]{"divu", rdReg, rs1Reg, rs2Reg});
            case 6:
                return normalView(new String[]{"rem", rdReg, rs1Reg, rs2Reg});
            case 7:
                return normalView(new String[]{"remu", rdReg, rs1Reg, rs2Reg});
            default:
                return unknown_command;
        }
    }

    private long unshuffle(long mask, int[] order, boolean signed) {
        long result = 0;
        for (int i = 0; i < order.length; i++) {
            long hit = Math.min(1, mask & (1L << i));
            if (i == order.length - 1 && signed) {
                result -= (hit << order[order.length - i - 1]);
            } else {
                result += (hit << order[order.length - i - 1]);
            }
        }
        return result;
    }

    // Immediate bits' orders for RVC
    private static final int[] immOrder1 = new int[]{5, 4, 9, 8, 7, 6, 2, 3};
    private static final int[] immOrder2 = new int[]{5, 4, 3, 2, 6};
    private static final int[] immOrder3 = new int[]{11, 4, 9, 8, 10, 6, 7, 3, 2, 1, 5};
    private static final int[] immOrder4 = new int[]{9, 4, 6, 8, 7, 5};
    private static final int[] immOrder5 = new int[]{17, 16, 15, 14, 13, 12};
    private static final int[] immOrder6 = new int[]{8, 4, 3, 7, 6, 2, 1, 5};
    private static final int[] immOrder7 = new int[]{5, 4, 3, 2, 7, 6};

    private String parseRVC(long mask) {
        String offset;
        switch ((int) bitSubstr(mask, 1, 0)) {
            case 0b00:
                switch ((int) bitSubstr(mask, 15, 13)) {
                    case 0b000:
                        if (bitSubstr(mask, 12, 2) == 0) {
                            return normalView(new String[]{"illegal_instruction"});
                        }
                        return normalView(new String[]{"c.addi4spn", TO_CREG(bitSubstr(mask, 4, 2)),
                                "sp", Long.toString(unshuffle(bitSubstr(mask, 12, 5), immOrder1, false))});
                    case 0b010:
                        return loadStoreView(new String[]{"c.lw", TO_CREG(bitSubstr(mask, 4, 2)),
                                Long.toString(unshuffle(bitSubstr(mask, 12, 10) * 4 + bitSubstr(mask, 6, 5), immOrder2, false)),
                                TO_CREG(bitSubstr(mask, 9, 7))});
                    case 0b110:
                        return loadStoreView(new String[]{"c.sw", TO_CREG(bitSubstr(mask, 4, 2)),
                                Long.toString(unshuffle(bitSubstr(mask, 12, 10) * 4 + bitSubstr(mask, 6, 5), immOrder2, false)),
                                TO_CREG(bitSubstr(mask, 9, 7))});
                    default:
                        return unknown_command;
                }
            case 0b01:
                switch ((int) bitSubstr(mask, 15, 13)) {
                    case 0b000:
                        if (bitSubstr(mask, 12, 2) == 0) {
                            return normalView(new String[]{"c.nop"});
                        } else if (bitSubstr(mask, 11, 7) != 0) {
                            return normalView(new String[]{"c.addi", TO_REG(bitSubstr(mask, 11, 7)),
                                    Long.toString(bitSubstr(mask, 12, 12) * 32 + bitSubstr(mask, 6, 2))});
                        }
                        return unknown_command;
                    case 0b001:
                        offset = Long.toHexString(pc + unshuffle(bitSubstr(mask, 12, 2), immOrder3, true));
                        return normalViewAddr(new String[]{"c.jal", offset});
                    case 0b010:
                        return normalView(new String[]{"c.li", TO_REG(bitSubstr(mask, 11, 7)),
                                Long.toString(bitSubstr(mask, 12, 12) * (-32) + bitSubstr(mask, 6, 2))});
                    case 0b011:
                        if (bitSubstr(mask, 11, 7) == 2) {
                            return normalView(new String[]{"c.addi16sp", TO_REG(bitSubstr(mask, 11, 7)),
                                    "sp", Long.toString(unshuffle(bitSubstr(mask, 12, 12) * 32 + bitSubstr(mask, 6, 2), immOrder4, true))});
                        } else if (bitSubstr(mask, 11, 7) != 0) {
                            return normalView(new String[]{"c.lui", TO_REG(bitSubstr(mask, 11, 7)),
                                    Long.toString(unshuffle(bitSubstr(mask, 12, 12) * 32 + bitSubstr(mask, 6, 2), immOrder5, true))});
                        }
                        return unknown_command;
                    case 0b100:
                        switch ((int) bitSubstr(mask, 11, 10)) {
                            case 0:
                                return normalView(new String[]{"c.srli", TO_CREG(bitSubstr(mask, 9, 7)),
                                        Long.toString(bitSubstr(mask, 12, 12) * 32 + bitSubstr(mask, 6, 2))});
                            case 1:
                                return normalView(new String[]{"c.srai", TO_CREG(bitSubstr(mask, 9, 7)),
                                        Long.toString(bitSubstr(mask, 12, 12) * 32 + bitSubstr(mask, 6, 2))});
                            case 2:
                                return normalView(new String[]{"c.andi", TO_CREG(bitSubstr(mask, 9, 7)),
                                        Long.toString(bitSubstr(mask, 12, 12) * (-32) + bitSubstr(mask, 6, 2))});
                            default:
                                switch ((int) bitSubstr(mask, 6, 5)) {
                                    case 0:
                                        return normalView(new String[]{"c.sub", TO_CREG(bitSubstr(mask, 9, 7)),
                                                TO_CREG(bitSubstr(mask, 4, 2))});
                                    case 1:
                                        return normalView(new String[]{"c.xor", TO_CREG(bitSubstr(mask, 9, 7)),
                                                TO_CREG(bitSubstr(mask, 4, 2))});
                                    case 2:
                                        return normalView(new String[]{"c.or", TO_CREG(bitSubstr(mask, 9, 7)),
                                                TO_CREG(bitSubstr(mask, 4, 2))});
                                    case 3:
                                        return normalView(new String[]{"c.and", TO_CREG(bitSubstr(mask, 9, 7)),
                                                TO_CREG(bitSubstr(mask, 4, 2))});
                                }
                        }
                    case 0b101:
                        offset = Long.toHexString(pc + unshuffle(bitSubstr(mask, 12, 2), immOrder3, true));
                        return normalViewAddr(new String[]{"c.j", offset});
                    case 0b110:
                        offset = Long.toHexString(pc + unshuffle(bitSubstr(mask, 12, 10) * 32 + bitSubstr(mask, 6, 2), immOrder6, true));
                        return normalViewAddr(new String[]{"c.beqz", TO_CREG(bitSubstr(mask, 9, 7)), offset});
                    case 0b111:
                        offset = Long.toHexString(pc + unshuffle(bitSubstr(mask, 12, 10) * 32 + bitSubstr(mask, 6, 2), immOrder6, true));
                        return normalViewAddr(new String[]{"c.bnez", TO_CREG(bitSubstr(mask, 9, 7)), offset});
                }
            case 0b10:
                switch ((int) bitSubstr(mask, 15, 13)) {
                    case 0b000:
                        return normalView(new String[]{"c.slli", TO_REG(bitSubstr(mask, 11, 7)),
                                Long.toString(bitSubstr(mask, 12, 12) * 32 + bitSubstr(mask, 6, 2))});
                    case 0b010:
                        offset = Long.toString(unshuffle(bitSubstr(mask, 12, 12) * 32 + bitSubstr(mask, 6, 2), immOrder7, false));
                        return loadStoreView(new String[]{"c.lwsp", TO_REG(bitSubstr(mask, 11, 7)), offset, "sp"});
                    case 0b100:
                        if (bitSubstr(mask, 12, 12) == 0) {
                            if (bitSubstr(mask, 11, 7) == 0) {
                                return unknown_command;
                            }
                            if (bitSubstr(mask, 6, 2) == 0) {
                                return normalView(new String[]{"c.jr", TO_REG(bitSubstr(mask, 11, 7))});
                            } else {
                                return normalView(new String[]{"c.mv", TO_REG(bitSubstr(mask, 11, 7)),
                                        TO_REG(bitSubstr(mask, 6, 2))});
                            }
                        } else {
                            if (bitSubstr(mask, 11, 7) == 0) {
                                if (bitSubstr(mask, 6, 2) == 0) {
                                    return normalView(new String[]{"c.ebreak"});
                                }
                                return unknown_command;
                            }
                            if (bitSubstr(mask, 6, 2) == 0) {
                                return normalView(new String[]{"c.jalr", TO_REG(bitSubstr(mask, 11, 7))});
                            } else {
                                return normalView(new String[]{"c.add", TO_REG(bitSubstr(mask, 11, 7)),
                                        TO_REG(bitSubstr(mask, 6, 2))});
                            }
                        }
                    case 0b110:
                        offset = Long.toString(unshuffle(bitSubstr(mask, 12, 7), immOrder7, false));
                        return loadStoreView(new String[]{"c.swsp", TO_REG(bitSubstr(mask, 6, 2)), offset, "sp"});
                }
            default:
                return unknown_command;
        }
    }
}
