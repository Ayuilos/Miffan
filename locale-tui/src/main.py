#!/usr/bin/env python3
"""Android Locale Manager TUI Application."""

import sys
import asyncio
from pathlib import Path

# Add src to path for imports
sys.path.insert(0, str(Path(__file__).parent))

import click
from config import Config
from app import LocaleTuiApp
from services.xml_parser import StringsXmlParser
from services.translator import AITranslator
from services import catalog
from models.entry import TranslationEntry


def load_config() -> Config:
    """Load configuration from file."""
    config_path = Path(__file__).parent.parent / "config.yml"

    if not config_path.exists():
        click.echo(f"错误：未找到配置文件 {config_path}", err=True)
        click.echo("请基于模板创建 config.yml 文件。", err=True)
        sys.exit(1)

    try:
        config = Config.load(config_path)
    except Exception as e:
        click.echo(f"错误：加载配置失败 - {e}", err=True)
        sys.exit(1)

    return config


def require_api_key(config: Config) -> None:
    """Only the TUI's AI translation and `add` without --skip-translate call the API."""
    if not config.openai_api_key:
        click.echo("警告：未设置 OPENAI_API_KEY。AI 翻译功能将无法使用。", err=True)


def select_module(config: Config, module: str | None):
    """Returns the named module, or the first configured one; exits on an unknown name."""
    if not config.modules:
        click.echo("错误：配置文件中未定义模块", err=True)
        sys.exit(1)
    if module is None:
        return config.modules[0]
    selected = next((m for m in config.modules if m.name == module), None)
    if not selected:
        click.echo(f"错误：未找到模块 '{module}'", err=True)
        click.echo(f"可用模块：{', '.join(m.name for m in config.modules)}", err=True)
        sys.exit(1)
    return selected


@click.group(invoke_without_command=True)
@click.pass_context
def cli(ctx):
    """Android Locale Manager - 管理和翻译 Android 字符串资源

    不带参数启动 TUI 界面，使用子命令进行命令行操作。
    """
    if ctx.invoked_subcommand is None:
        # No command provided, launch TUI
        config = load_config()
        require_api_key(config)
        app = LocaleTuiApp(config)
        app.run()


@cli.command("test-connection")
def test_connection():
    """测试 AI 服务连接

    \b
    示例：
        locale-tui test-connection
    """
    config = load_config()

    if not config.openai_api_key:
        click.echo("错误：未设置 OPENAI_API_KEY，无法测试连接。", err=True)
        sys.exit(1)

    click.echo("AI 服务配置：")
    click.echo(f"  Base URL: {config.openai_base_url}")
    click.echo(f"  Model: {config.translation_model}")
    click.echo("正在测试连接...")

    async def test_async():
        translator = AITranslator(config)
        return await translator.test_connection()

    try:
        content = asyncio.run(test_async())
        click.echo("✓ 连接成功")
        if content:
            click.echo(f"响应: {content}")
        else:
            click.echo("响应为空，但 API 已返回有效结果。")
    except Exception as e:
        click.echo(f"✗ 连接失败: {e}", err=True)
        sys.exit(1)


@cli.command()
@click.argument("key")
@click.argument("value")
@click.option(
    "--module",
    "-m",
    default=None,
    help="模块名称（默认使用配置文件中的第一个模块）",
)
@click.option("--skip-translate", is_flag=True, help="跳过自动翻译，仅添加源语言条目")
def add(key: str, value: str, module: str, skip_translate: bool):
    """添加新的语言条目并自动翻译

    \b
    示例：
        locale-tui add hello_world "Hello, World!"
        locale-tui add greeting "Welcome" -m app
        locale-tui add test_key "Test" --skip-translate
    """
    config = load_config()

    # Select module
    if module:
        selected_module = next((m for m in config.modules if m.name == module), None)
        if not selected_module:
            click.echo(f"错误：未找到模块 '{module}'", err=True)
            click.echo(f"可用模块：{', '.join(m.name for m in config.modules)}", err=True)
            sys.exit(1)
    else:
        if not config.modules:
            click.echo("错误：配置文件中未定义模块", err=True)
            sys.exit(1)
        selected_module = config.modules[0]

    click.echo(f"使用模块: {selected_module.name}")

    # Get source language
    source_lang = config.get_source_language()
    if not source_lang:
        click.echo("错误：未配置源语言", err=True)
        sys.exit(1)

    # Resolve res directory
    res_dir = config.project_root / selected_module.res_path
    if not res_dir.exists():
        click.echo(f"错误：资源目录不存在 {res_dir}", err=True)
        sys.exit(1)

    # Add entry to source language file
    source_file = res_dir / "values" / "strings.xml"
    click.echo(f"添加条目到 {source_file.relative_to(config.project_root)}...")

    try:
        StringsXmlParser.update_entry(source_file, key, value)
        click.echo(f"✓ 已添加条目: {key} = {value}")
    except Exception as e:
        click.echo(f"错误：添加条目失败 - {e}", err=True)
        sys.exit(1)

    # Translate to other languages
    if not skip_translate:
        require_api_key(config)
        target_languages = [lang.code for lang in config.languages if not lang.is_source]

        if not target_languages:
            click.echo("未配置目标语言，跳过翻译。")
            return

        click.echo(f"开始翻译到 {len(target_languages)} 种语言...")

        # Create entry for translation
        entry = TranslationEntry(key=key, translations={"values": value})

        async def translate_async():
            translator = AITranslator(config)

            async def translate_one(lang_code: str):
                lang_name = config.get_language_name(lang_code)

                try:
                    translations = await translator.translate_batch(
                        {key: value}, lang_name
                    )

                    if key in translations:
                        return lang_code, lang_name, translations[key], None
                    return lang_code, lang_name, None, "翻译失败（未返回结果）"
                except Exception as e:
                    return lang_code, lang_name, None, str(e)

            tasks = [translate_one(lang_code) for lang_code in target_languages]
            results = await asyncio.gather(*tasks)

            for lang_code, lang_name, translated_value, error in results:
                click.echo(f"翻译到 {lang_name}...", nl=False)

                if error:
                    click.echo(f" ✗ 错误: {error}", err=True)
                    continue

                entry.set_translation(lang_code, translated_value)

                # Save to file
                target_file = res_dir / lang_code / "strings.xml"
                StringsXmlParser.update_entry(target_file, key, translated_value)

                click.echo(f" ✓ {translated_value}")

        asyncio.run(translate_async())
        click.echo("完成！")


@cli.command()
@click.argument("key")
@click.argument("value")
@click.option(
    "--lang",
    "-l",
    default=None,
    help="语言代码（例如：values, values-zh, values-ja），默认为源语言",
)
@click.option(
    "--module",
    "-m",
    default=None,
    help="模块名称（默认使用配置文件中的第一个模块）",
)
def set(key: str, value: str, lang: str, module: str):
    """手动设置指定语言的条目值

    \b
    示例：
        locale-tui set hello_world "你好，世界！" -l values-zh
        locale-tui set greeting "Welcome" -l values
        locale-tui set test_key "テスト" -l values-ja -m app
    """
    config = load_config()

    # Select module
    if module:
        selected_module = next((m for m in config.modules if m.name == module), None)
        if not selected_module:
            click.echo(f"错误：未找到模块 '{module}'", err=True)
            click.echo(f"可用模块：{', '.join(m.name for m in config.modules)}", err=True)
            sys.exit(1)
    else:
        if not config.modules:
            click.echo("错误：配置文件中未定义模块", err=True)
            sys.exit(1)
        selected_module = config.modules[0]

    # Resolve language directory
    if lang is None:
        lang = "values"  # Default to source language

    # Resolve res directory
    res_dir = config.project_root / selected_module.res_path
    if not res_dir.exists():
        click.echo(f"错误：资源目录不存在 {res_dir}", err=True)
        sys.exit(1)

    # Target file
    target_file = res_dir / lang / "strings.xml"
    lang_name = config.get_language_name(lang) if lang != "values" else "源语言"

    click.echo(f"设置 {lang_name} 的条目: {key} = {value}")
    click.echo(f"目标文件: {target_file.relative_to(config.project_root)}")

    try:
        StringsXmlParser.update_entry(target_file, key, value)
        click.echo(f"✓ 设置成功")
    except Exception as e:
        click.echo(f"错误：设置失败 - {e}", err=True)
        sys.exit(1)


@cli.command()
@click.option(
    "--module",
    "-m",
    default=None,
    help="模块名称（默认使用配置文件中的第一个模块）",
)
def list_keys(module: str):
    """列出所有语言条目的键

    \b
    示例：
        locale-tui list-keys
        locale-tui list-keys -m app
    """
    config = load_config()

    # Select module
    if module:
        selected_module = next((m for m in config.modules if m.name == module), None)
        if not selected_module:
            click.echo(f"错误：未找到模块 '{module}'", err=True)
            sys.exit(1)
    else:
        if not config.modules:
            click.echo("错误：配置文件中未定义模块", err=True)
            sys.exit(1)
        selected_module = config.modules[0]

    # Resolve res directory
    res_dir = config.project_root / selected_module.res_path
    source_file = res_dir / "values" / "strings.xml"

    if not source_file.exists():
        click.echo(f"错误：源文件不存在 {source_file}", err=True)
        sys.exit(1)

    # Parse and display
    entries = StringsXmlParser.parse(source_file)

    click.echo(f"模块 '{selected_module.name}' 共有 {len(entries)} 个条目：")
    click.echo()

    for key in sorted(entries.keys()):
        value = entries[key]
        # Truncate long values
        if len(value) > 60:
            value = value[:57] + "..."
        click.echo(f"  {key:40} {value}")


@cli.command()
@click.argument("json_file", type=click.Path(exists=True, dir_okay=False, path_type=Path))
@click.option("--module", "-m", default=None, help="模块名称（默认使用配置文件中的第一个模块）")
@click.option("--dry-run", is_flag=True, help="只校验，不写入")
def apply(json_file: Path, module: str, dry_run: bool):
    """批量写入由 AI 完成的翻译（不调用任何 API）

    \b
    JSON 格式：{"key": {"values": "English", "values-zh": "中文", ...}, ...}
    已有的 key 可以只给需要更新的语言；新 key 必须给出全部语言。
    撇号和双引号会自动转义，占位符必须与英文一致。

    \b
    示例：
        locale-tui apply /tmp/strings.json
        locale-tui apply /tmp/strings.json -m search --dry-run
    """
    import json

    config = load_config()
    selected = select_module(config, module)
    res_dir = config.project_root / selected.res_path
    languages = config.get_language_codes()
    try:
        raw = json.loads(json_file.read_text(encoding="utf-8"))
    except json.JSONDecodeError as e:
        click.echo(f"错误：JSON 无效 - {e}", err=True)
        sys.exit(1)

    entries = {
        key: {lang: catalog.escape_android(value) for lang, value in values.items()}
        for key, values in raw.items()
    }
    existing = catalog.read_strings(res_dir / "values" / "strings.xml")
    # Existing keys may update only some languages; new keys must be complete.
    problems = [
        p for p in catalog.validate(entries, languages, existing)
        if not (p.message == "missing" and p.key in existing)
    ]
    if problems:
        click.echo(f"✗ {len(problems)} 个问题，未写入：", err=True)
        for problem in problems:
            click.echo(f"  {problem}", err=True)
        sys.exit(1)

    by_lang: dict[str, dict[str, str]] = {}
    for key, values in entries.items():
        for lang, value in values.items():
            by_lang.setdefault(lang, {})[key] = value
    for lang, values in by_lang.items():
        target = res_dir / lang / "strings.xml"
        if not dry_run:
            catalog.write_entries(target, values)
        click.echo(f"{'校验通过' if dry_run else '✓ 已写入'} {target.relative_to(config.project_root)}：{len(values)} 条")


@cli.command()
@click.option("--module", "-m", default=None, help="模块名称（默认使用配置文件中的第一个模块）")
@click.option("--json", "as_json", is_flag=True, help="以 JSON 输出，便于交给 AI 翻译")
def missing(module: str, as_json: bool):
    """列出缺少翻译的 key，以及英文源文本里仍是中文的 key

    \b
    示例：
        locale-tui missing
        locale-tui missing -m search --json
    """
    import json

    config = load_config()
    selected = select_module(config, module)
    report = catalog.find_missing(config.project_root / selected.res_path, config.get_language_codes())
    if as_json:
        click.echo(json.dumps(report, ensure_ascii=False, indent=2))
        return
    if not report:
        click.echo(f"✓ 模块 '{selected.name}' 的翻译完整")
        return
    click.echo(f"模块 '{selected.name}' 有 {len(report)} 个 key 需要处理：")
    for key, issues in report.items():
        langs = ", ".join(lang for lang in issues if lang != "source")
        click.echo(f"  {key:40} {langs}")


@cli.command()
@click.option("--module", "-m", default=None, help="模块名称（默认使用配置文件中的第一个模块）")
def hardcoded(module: str):
    """扫描源码中含中文的字符串字面量（应改为字符串资源的候选）

    \b
    示例：
        locale-tui hardcoded
        locale-tui hardcoded -m search
    """
    config = load_config()
    selected = select_module(config, module)
    files = sorted({
        path
        for pattern in selected.source_patterns
        for path in config.project_root.glob(pattern)
        if path.suffix in (".kt", ".java")
    })
    hits = catalog.scan_hardcoded(files, config.project_root)
    for file, line, literal in hits:
        click.echo(f"{file}:{line}: \"{literal}\"")
    click.echo(f"共 {len(hits)} 处")


def main():
    """Main entry point."""
    cli()


if __name__ == "__main__":
    main()
