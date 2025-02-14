package com.denizenscript.denizen.scripts.commands.entity;

import com.denizenscript.denizen.utilities.debugging.Debug;
import com.denizenscript.denizen.objects.EntityTag;
import com.denizenscript.denizen.objects.properties.entity.EntityAge;
import com.denizenscript.denizencore.exceptions.InvalidArgumentsException;
import com.denizenscript.denizencore.objects.Argument;
import com.denizenscript.denizencore.objects.core.ElementTag;
import com.denizenscript.denizencore.objects.ArgumentHelper;
import com.denizenscript.denizencore.objects.core.ListTag;
import com.denizenscript.denizencore.scripts.ScriptEntry;
import com.denizenscript.denizencore.scripts.commands.AbstractCommand;

import java.util.List;

public class AgeCommand extends AbstractCommand {

    public AgeCommand() {
        setName("age");
        setSyntax("age [<entity>|...] (adult/baby/<age>) (lock)");
        setRequiredArguments(1, 3);
        isProcedural = false;
    }

    // <--[command]
    // @Name Age
    // @Syntax age [<entity>|...] (adult/baby/<age>) (lock)
    // @Required 1
    // @Maximum 3
    // @Short Sets the ages of a list of entities, optionally locking them in those ages.
    // @Group entity
    //
    // @Description
    // Some living entity types are 'ageable' which can affect an entities ability to breed, or whether they appear as a baby or an adult.
    // Using the 'age' command allows modification of an entity's age.
    // Specify an entity and either 'baby', 'adult', or an integer age to set the age of an entity.
    // Using the 'lock' argument will keep the entity from increasing its age automatically.
    // NPCs which use ageable entity types can also be specified.
    //
    // @Tags
    // <EntityTag.age>
    //
    // @Usage
    // Use to make an ageable entity a permanant baby.
    // - age <[some_entity]> baby lock
    //
    // @Usage
    // ...or a mature adult.
    // - age <[some_entity]> adult lock
    //
    // @Usage
    // Use to make a baby entity an adult.
    // - age <[some_npc]> adult
    //
    // @Usage
    // Use to mature some animals so that they are old enough to breed.
    // - age <player.location.find_entities.within[20]> adult
    // -->

    private enum AgeType {ADULT, BABY}

    @Override
    public void parseArgs(ScriptEntry scriptEntry) throws InvalidArgumentsException {

        for (Argument arg : scriptEntry.getProcessedArgs()) {

            if (!scriptEntry.hasObject("entities")
                    && arg.matchesArgumentList(EntityTag.class)) {
                scriptEntry.addObject("entities", arg.asType(ListTag.class).filter(EntityTag.class, scriptEntry));
            }
            else if (!scriptEntry.hasObject("agetype")
                    && arg.matchesEnum(AgeType.values())) {
                scriptEntry.addObject("agetype", AgeType.valueOf(arg.getValue().toUpperCase()));
            }
            else if (!scriptEntry.hasObject("age")
                    && arg.matchesInteger()) {
                scriptEntry.addObject("age", arg.asElement());
            }
            else if (!scriptEntry.hasObject("lock")
                    && arg.matches("lock")) {
                scriptEntry.addObject("lock", new ElementTag(true));
            }
            else {
                arg.reportUnhandled();
            }
        }

        // Check to make sure required arguments have been filled
        if (!scriptEntry.hasObject("entities")) {
            throw new InvalidArgumentsException("No valid entities specified.");
        }

        // Use default age if one is not specified
        scriptEntry.defaultObject("age", new ElementTag(1));

    }

    @Override
    public void execute(final ScriptEntry scriptEntry) {
        List<EntityTag> entities = (List<EntityTag>) scriptEntry.getObject("entities");
        AgeType ageType = (AgeType) scriptEntry.getObject("agetype");
        int age = scriptEntry.getElement("age").asInt();
        boolean lock = scriptEntry.hasObject("lock");

        if (scriptEntry.dbCallShouldDebug()) {
            Debug.report(scriptEntry, getName(), (lock ? ArgumentHelper.debugObj("lock", true) : "") +
                    (ageType != null ? ArgumentHelper.debugObj("agetype", ageType)
                            : ArgumentHelper.debugObj("age", age)) +
                    ArgumentHelper.debugObj("entities", entities.toString()));
        }

        // Go through all the entities and set their ages
        for (EntityTag entity : entities) {
            if (entity.isSpawned()) {

                // Check if entity specified can be described by 'EntityAge'
                if (EntityAge.describes(entity)) {

                    EntityAge property = EntityAge.getFrom(entity);

                    // Adjust 'ageType'
                    if (ageType != null) {
                        if (ageType.equals(AgeType.BABY)) {
                            property.setBaby(true);
                        }
                        else {
                            property.setBaby(false);
                        }
                    }
                    else {
                        property.setAge(age);
                    }

                    // Adjust 'locked'
                    property.setLock(lock);
                }
                else {
                    Debug.echoError(scriptEntry.getResidingQueue(), entity.identify() + " is not ageable!");
                }

            }
        }

    }
}
