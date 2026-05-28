# Framework integrations for FiGuard.
#
# Each integration is an optional extra — install only what you need:
#
#   pip install figuard[langchain]       # LangChain + LangGraph
#   pip install figuard[crewai]          # CrewAI
#   pip install figuard[openai-agents]   # OpenAI Agents SDK
#   pip install figuard[openai]          # Raw OpenAI function calling
#   pip install figuard[anthropic]       # Anthropic tool use
#   pip install figuard[all]             # Everything
#
# Zero-config one-liners (use the shared public sandbox by default):
#
#   from figuard.integrations.langchain import auto_guard_langchain
#   executor = auto_guard_langchain(executor)           # $500 / 24h budget, auto-wired
#
#   from figuard.integrations.crewai import auto_guard_crewai
#   auto_guard_crewai(my_tool)                          # wraps tool._run in-place
