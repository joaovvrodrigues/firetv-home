"""Limites de seguranca do planejamento, sem acesso ao aparelho."""
def select_packages(candidates, installed, keep, never, third_party):
    return sorted(set(candidates) & set(installed) - set(keep) - set(never) - set(third_party))

def can_restrict_uid(uid, sharing, targets):
    return uid >= 10000 and bool(sharing) and set(sharing) <= set(targets)
