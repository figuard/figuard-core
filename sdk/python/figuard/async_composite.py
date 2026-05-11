"""
AsyncCompositeGuard — async variant of CompositeGuard for agents using AsyncFiGuardClient.

Usage::

    from figuard import AsyncFiGuardClient
    from figuard.async_composite import AsyncCompositeGuard, AsyncGuardedResource

    async with AsyncFiGuardClient(api_key=...) as client:
        guard = AsyncCompositeGuard([
            AsyncGuardedResource(client=client, session_token=token_a, resource="tokens"),
            AsyncGuardedResource(client=client, session_token=token_b, resource="USD"),
        ])

        result = await guard.authorize(
            agent_id="travel_agent",
            action_type="LLM_CALL",
            description="search flights",
            requested={"tokens": 1500, "USD": 0.09},
            idempotency_key=str(uuid4()),
        )

        if result.all_authorized:
            await guard.confirm(result, confirmed={"tokens": 1423, "USD": 0.085})
"""

from __future__ import annotations

import logging
from dataclasses import dataclass
from typing import Any, Dict, List, Optional
from uuid import uuid4

from .composite import CompositeAuthorizationResult
from .models import SpendEventResponse

logger = logging.getLogger(__name__)


@dataclass
class AsyncGuardedResource:
    """Pairs an AsyncFiGuardClient + session_token with a resource label."""
    client: Any  # AsyncFiGuardClient — avoid circular import
    session_token: str
    resource: str


class AsyncCompositeGuard:
    """
    Async multi-resource guard for use with AsyncFiGuardClient.

    Authorizes resources sequentially (not concurrently) to ensure correct
    void-on-partial-denial semantics. If Budget A authorizes and Budget B
    denies, Budget A is voided before returning the denial result.
    """

    def __init__(self, resources: List[AsyncGuardedResource]) -> None:
        if not resources:
            raise ValueError("AsyncCompositeGuard requires at least one AsyncGuardedResource")
        self._resources = resources

    async def authorize(
        self,
        agent_id: str,
        action_type: str,
        description: str,
        requested: Dict[str, float],
        idempotency_key: Optional[str] = None,
        trace_id: Optional[str] = None,
        **kwargs: Any,
    ) -> CompositeAuthorizationResult:
        key = idempotency_key or str(uuid4())
        authorized = []

        for resource in self._resources:
            qty = requested.get(resource.resource, 0.0)
            try:
                result = await resource.client.authorize(
                    session_token=resource.session_token,
                    agent_id=agent_id,
                    action_type=action_type,
                    description=description,
                    requested_quantity=qty,
                    idempotency_key=f"{key}:{resource.resource}",
                    trace_id=trace_id,
                    **kwargs,
                )
            except Exception as exc:
                logger.error(
                    "AsyncCompositeGuard authorize error — resource=%s: %s",
                    resource.resource, exc,
                )
                await self._void_all(authorized, reason="COMPOSITE_AUTHORIZE_ERROR")
                raise

            if not result.is_authorized:
                await self._void_all(authorized, reason="COMPOSITE_PARTIAL_DENIAL")
                all_results = [r for _, r in authorized] + [result]
                resources_so_far = [r.resource for r, _ in authorized] + [resource.resource]
                return CompositeAuthorizationResult(
                    resources=resources_so_far,
                    authorizations=all_results,
                    all_authorized=False,
                    first_denial=result,
                    first_denial_resource=resource.resource,
                )

            authorized.append((resource, result))

        return CompositeAuthorizationResult(
            resources=[r.resource for r in self._resources],
            authorizations=[r for _, r in authorized],
            all_authorized=True,
        )

    async def confirm(
        self,
        result: CompositeAuthorizationResult,
        confirmed: Dict[str, float],
    ) -> List[SpendEventResponse]:
        events = []
        for resource, auth in zip(self._resources, result.authorizations):
            if not auth.is_authorized:
                continue
            qty = confirmed.get(resource.resource, 0.0)
            try:
                event = await resource.client.confirm_event(
                    auth.event_id, confirmed_quantity=qty
                )
                events.append(event)
            except Exception as exc:
                logger.warning(
                    "AsyncCompositeGuard confirm swallowed — resource=%s event=%s: %s",
                    resource.resource, auth.event_id, exc,
                )
        return events

    async def fail(
        self,
        result: CompositeAuthorizationResult,
        reason: str = "TOOL_ERROR",
        error_message: Optional[str] = None,
    ) -> None:
        for resource, auth in zip(self._resources, result.authorizations):
            if not auth.is_authorized:
                continue
            try:
                await resource.client.fail_event(
                    auth.event_id, reason=reason, error_message=error_message
                )
            except Exception as exc:
                logger.warning(
                    "AsyncCompositeGuard fail_event error — resource=%s event=%s: %s",
                    resource.resource, auth.event_id, exc,
                )

    async def _void_all(self, authorized, reason: str) -> None:
        for resource, auth in authorized:
            try:
                await resource.client.void_event(auth.event_id, reason=reason)
            except Exception as exc:
                logger.warning(
                    "AsyncCompositeGuard void_event error — resource=%s event=%s: %s",
                    resource.resource, auth.event_id, exc,
                )
