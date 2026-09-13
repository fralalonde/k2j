package accept.covariant

interface IEntityId

class ActivityId : IEntityId

interface IActivityCommand {
    val id: IEntityId
}

class CancelActivityLike(override val id: ActivityId) : IActivityCommand
