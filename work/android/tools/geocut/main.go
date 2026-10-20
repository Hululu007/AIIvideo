// geocut —— 裁剪 v2ray 的 geosite.dat / geoip.dat，并从保留的分类里剔除色情域名。
//
// 背景：libv2ray.aar 自带 domain-list-community 的全量快照（geosite.dat 11MB、
// geoip.dat 17MB），而 XrayConfig 只写了 geosite:cn 一条规则。用不到的 200+
// 分类白占体积，且其中的色情域名表会被 APK 安全扫描当成内容特征 —— 华为/
// 腾讯的 a.gray.sex* 判定里，域名词典是主要依据之一。
//
// 做法分两步：
//   1. 分类裁剪  只保留 -keep 列出的 country_code。
//   2. 域名剔黑  从全量文件里取出 CATEGORY-PORN 分类的域名，构成黑名单，
//                把保留分类里落在黑名单上的域名删掉；-drop 额外补子串。
//                上游的 CN 列表里混着 51cg.com 这类站，光靠分类裁剪删不掉。
//
// 用法:
//   go run . -in geosite.dat -out geosite.min.dat -keep CN
//   go run . -in geoip.dat   -out geoip.min.dat   -keep CN -noblacklist
package main

import (
	"flag"
	"fmt"
	"os"
	"strings"
)

// ── protobuf wire format 的最小实现 ────────────────────────────────
// 只处理 length-delimited 与 varint，够解析 geosite/geoip 了。

func readVarint(b []byte, i int) (uint64, int, error) {
	var x uint64
	var s uint
	for {
		if i >= len(b) {
			return 0, i, fmt.Errorf("varint 越界 @%d", i)
		}
		c := b[i]
		i++
		if c < 0x80 {
			return x | uint64(c)<<s, i, nil
		}
		x |= uint64(c&0x7f) << s
		s += 7
		if s > 63 {
			return 0, i, fmt.Errorf("varint 过长 @%d", i)
		}
	}
}

func putVarint(out []byte, v uint64) []byte {
	for v >= 0x80 {
		out = append(out, byte(v)|0x80)
		v >>= 7
	}
	return append(out, byte(v))
}

func putBytes(out []byte, tag byte, val []byte) []byte {
	out = append(out, tag)
	out = putVarint(out, uint64(len(val)))
	return append(out, val...)
}

// field 把一段 message 按 (field, wire, 原始值) 拆开。raw 是 value 的字节，
// varint 字段的 raw 是编码后的变长整数（原样搬运即可）。
type field struct {
	num  uint64
	wire uint64
	raw  []byte
}

func parseFields(b []byte) ([]field, error) {
	var out []field
	i := 0
	for i < len(b) {
		tag, n, err := readVarint(b, i)
		if err != nil {
			return nil, err
		}
		i = n
		num, wire := tag>>3, tag&7
		switch wire {
		case 0:
			_, n, err := readVarint(b, i)
			if err != nil {
				return nil, err
			}
			out = append(out, field{num, wire, b[i:n]})
			i = n
		case 1:
			if i+8 > len(b) {
				return nil, fmt.Errorf("fixed64 越界")
			}
			out = append(out, field{num, wire, b[i : i+8]})
			i += 8
		case 2:
			ln, n, err := readVarint(b, i)
			if err != nil {
				return nil, err
			}
			i = n
			if i+int(ln) > len(b) {
				return nil, fmt.Errorf("length-delimited 越界")
			}
			out = append(out, field{num, wire, b[i : i+int(ln)]})
			i += int(ln)
		case 5:
			if i+4 > len(b) {
				return nil, fmt.Errorf("fixed32 越界")
			}
			out = append(out, field{num, wire, b[i : i+4]})
			i += 4
		default:
			return nil, fmt.Errorf("不支持的 wire type %d", wire)
		}
	}
	return out, nil
}

// ── geosite / geoip 的语义层 ──────────────────────────────────────
// GeoSiteList { repeated GeoSite entry = 1 }
// GeoSite     { string country_code = 1; repeated Domain domain = 2 }
// Domain      { Type type = 1; string value = 2; repeated Attribute attribute = 3 }
// GeoIPList   { repeated GeoIP entry = 1 }
// GeoIP       { string country_code = 1; repeated CIDR cidr = 2 }

type entry struct {
	code   string
	fields []field // 除 country_code 外的原始字段，重建时原样写回
}

func parseList(raw []byte) ([]entry, error) {
	top, err := parseFields(raw)
	if err != nil {
		return nil, err
	}
	var out []entry
	for _, f := range top {
		if f.num != 1 || f.wire != 2 {
			continue
		}
		inner, err := parseFields(f.raw)
		if err != nil {
			return nil, err
		}
		e := entry{}
		for _, g := range inner {
			if g.num == 1 && g.wire == 2 {
				e.code = string(g.raw)
			} else {
				e.fields = append(e.fields, g)
			}
		}
		out = append(out, e)
	}
	return out, nil
}

func (e entry) encode() []byte {
	var b []byte
	b = putBytes(b, 0x0a, []byte(e.code))
	for _, f := range e.fields {
		switch f.wire {
		case 0:
			b = append(b, byte(f.num<<3))
			b = append(b, f.raw...)
		case 2:
			b = putBytes(b, byte(f.num<<3|2), f.raw)
		}
	}
	return b
}

// domainValue 取 Domain.value（field 2）。
func domainValue(raw []byte) string {
	fs, err := parseFields(raw)
	if err != nil {
		return ""
	}
	for _, f := range fs {
		if f.num == 2 && f.wire == 2 {
			return string(f.raw)
		}
	}
	return ""
}

func main() {
	in := flag.String("in", "", "输入 .dat")
	out := flag.String("out", "", "输出 .dat")
	keep := flag.String("keep", "CN", "逗号分隔的 country_code 白名单")
	dropsite := flag.String("dropsite", "CATEGORY-PORN", "从输入文件里取哪个分类当域名黑名单")
	extra := flag.String("drop", "51cg", "额外按子串剔的域名关键词，逗号分隔")
	noblacklist := flag.Bool("noblacklist", false, "跳过域名剔黑（geoip 用，CIDR 没有域名）")
	flag.Parse()
	if *in == "" || *out == "" {
		fmt.Fprintln(os.Stderr, "需要 -in 和 -out")
		os.Exit(2)
	}

	raw, err := os.ReadFile(*in)
	if err != nil {
		fmt.Fprintln(os.Stderr, err)
		os.Exit(1)
	}
	all, err := parseList(raw)
	if err != nil {
		fmt.Fprintln(os.Stderr, "解析失败:", err)
		os.Exit(1)
	}

	// 黑名单：CATEGORY-PORN 的域名全集 + 额外子串。
	black := map[string]bool{}
	var blackCount int
	if !*noblacklist {
		for _, e := range all {
			if !strings.EqualFold(e.code, *dropsite) {
				continue
			}
			for _, f := range e.fields {
				if f.num == 2 && f.wire == 2 {
					if v := domainValue(f.raw); v != "" {
						black[strings.ToLower(v)] = true
						blackCount++
					}
				}
			}
		}
	}
	var substrings []string
	for _, s := range strings.Split(*extra, ",") {
		if s = strings.TrimSpace(strings.ToLower(s)); s != "" {
			substrings = append(substrings, s)
		}
	}

	want := map[string]bool{}
	for _, k := range strings.Split(*keep, ",") {
		if k = strings.TrimSpace(strings.ToUpper(k)); k != "" {
			want[k] = true
		}
	}

	var result []byte
	total, kept, dropped := 0, 0, 0
	var keptCodes []string

	for _, e := range all {
		total++
		if !want[strings.ToUpper(e.code)] {
			continue
		}
		if !*noblacklist {
			var keptFields []field
			for _, f := range e.fields {
				hit := false
				if f.num == 2 && f.wire == 2 {
					v := strings.ToLower(domainValue(f.raw))
					if black[v] {
						hit = true
					}
					for _, s := range substrings {
						if strings.Contains(v, s) {
							hit = true
						}
					}
				}
				if hit {
					dropped++
					continue
				}
				keptFields = append(keptFields, f)
			}
			e.fields = keptFields
		}
		kept++
		keptCodes = append(keptCodes, e.code)
		result = putBytes(result, 0x0a, e.encode())
	}

	if err := os.WriteFile(*out, result, 0o644); err != nil {
		fmt.Fprintln(os.Stderr, err)
		os.Exit(1)
	}
	fmt.Printf("%s\n  分类 %d → 保留 %d %v\n", *in, total, kept, keptCodes)
	if !*noblacklist {
		fmt.Printf("  黑名单 %d 个域名（来自 %s）+ 子串 %v → 剔除 %d 条\n",
			blackCount, *dropsite, substrings, dropped)
	}
	fmt.Printf("  %d 字节 → %d 字节 (%.1f%%)\n", len(raw), len(result),
		100*float64(len(result))/float64(len(raw)))
}
